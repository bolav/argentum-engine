package com.wingedsheep.gameserver.handler

import com.wingedsheep.ai.engine.SealedDeckGenerator
import com.wingedsheep.gameserver.ai.AiGameManager
import com.wingedsheep.gameserver.config.GameProperties
import com.wingedsheep.gameserver.deck.DeckValidator
import com.wingedsheep.gameserver.lobby.QuickGameLobby
import com.wingedsheep.gameserver.lobby.QuickGameLobbyPlayer
import com.wingedsheep.gameserver.lobby.QuickGameLobbyRepository
import com.wingedsheep.gameserver.protocol.ClientMessage
import com.wingedsheep.gameserver.protocol.ErrorCode
import com.wingedsheep.gameserver.protocol.ServerMessage
import com.wingedsheep.gameserver.repository.GameRepository
import com.wingedsheep.gameserver.repository.LobbyRepository
import com.wingedsheep.gameserver.session.GameSession
import com.wingedsheep.gameserver.session.PlayerSession
import com.wingedsheep.gameserver.session.SessionRegistry
import com.wingedsheep.engine.registry.CardRegistry
import com.wingedsheep.sdk.core.DeckFormat
import com.wingedsheep.sdk.model.Deck
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketSession

/**
 * Lifecycle handler for the Quick Game lobby — the staging area users see between picking
 * "Quick Game" on the home screen and the actual match start. Replaces the old in-place
 * `waitingGameSession: @Volatile` field on [GamePlayHandler] with proper per-lobby locking
 * via [QuickGameLobbyRepository].
 *
 * Responsibilities:
 *  - Create / join / leave a lobby.
 *  - Accept and validate per-player deck submissions (server-authoritative validation via
 *    [DeckValidator]; an empty deck list is accepted and means "use the random sealed pool").
 *  - Track ready state per player.
 *  - When all players in the lobby are ready, hand off to [GamePlayHandler.startQuickGameFromLobby]
 *    which builds the [GameSession], wires AI if needed, and sends `GameStarted`.
 *
 * AI lobbies (`vsAi = true`): the AI is added immediately at lobby creation and is auto-ready.
 * The host therefore only has to pick a deck (or stay on Random) and click Ready, giving the
 * fast UX we wanted while still routing through one consistent surface.
 */
@Component
class QuickGameLobbyHandler(
    private val sessionRegistry: SessionRegistry,
    private val lobbyRepository: QuickGameLobbyRepository,
    private val tournamentLobbyRepository: LobbyRepository,
    private val gameRepository: GameRepository,
    private val sender: MessageSender,
    private val cardRegistry: CardRegistry,
    private val printingRegistry: com.wingedsheep.engine.registry.PrintingRegistry,
    private val deckValidator: DeckValidator,
    private val deckGenerator: SealedDeckGenerator,
    private val gameProperties: GameProperties,
    private val aiGameManager: AiGameManager,
    private val gamePlayHandler: GamePlayHandler,
) {
    private val logger = LoggerFactory.getLogger(QuickGameLobbyHandler::class.java)

    /**
     * Optional delegate for tournament lobby joins. Wired by [GameWebSocketHandler.wireCallbacks]
     * so that a code typed in the home-screen "Join" field can land in either a quick-game lobby
     * or a tournament lobby without the user having to know which is which.
     */
    var joinTournamentLobbyCallback: ((WebSocketSession, ClientMessage.JoinLobby) -> Unit)? = null

    fun handle(session: WebSocketSession, message: ClientMessage) {
        when (message) {
            is ClientMessage.CreateQuickGameLobby -> handleCreate(session, message)
            is ClientMessage.JoinQuickGameLobby -> handleJoin(session, message)
            is ClientMessage.LeaveQuickGameLobby -> handleLeave(session)
            is ClientMessage.SubmitQuickGameLobbyDeck -> handleSubmitDeck(session, message)
            is ClientMessage.SetQuickGameLobbyAiDeck -> handleSetAiDeck(session, message)
            is ClientMessage.SetQuickGameLobbyReady -> handleSetReady(session, message)
            is ClientMessage.SetQuickGameLobbySetCode -> handleSetSetCode(session, message)
            is ClientMessage.SetQuickGameLobbyPublic -> handleSetPublic(session, message)
            is ClientMessage.SetQuickGameLobbyFormat -> handleSetFormat(session, message)
            else -> {}
        }
    }

    private fun handleSetFormat(session: WebSocketSession, message: ClientMessage.SetQuickGameLobbyFormat) {
        val playerSession = sessionRegistry.getPlayerSession(session.id) ?: run {
            sender.sendError(session, ErrorCode.NOT_CONNECTED, "Not connected"); return
        }
        val lobby = lobbyRepository.findContainingPlayer(playerSession.playerId) ?: run {
            sender.sendError(session, ErrorCode.GAME_NOT_FOUND, "Not in a lobby"); return
        }
        lobbyRepository.withLock(lobby.lobbyId) { current ->
            if (current == null) return@withLock
            val host = current.players.firstOrNull { !it.isAi }
            if (host?.playerId != playerSession.playerId) {
                sender.sendError(session, ErrorCode.INVALID_ACTION, "Only the host can change the format")
                return@withLock
            }
            if (current.format == message.format) return@withLock
            current.format = message.format
            // Re-validate every submitted deck under the new format. Submissions that no longer
            // pass un-ready the player so they have to update their deck or accept a new format.
            for (player in current.players) {
                if (player.isAi) continue
                val deck = player.deckList ?: continue
                if (deck.isEmpty()) continue // Random pool — format restriction doesn't apply.
                val result = deckValidator.validate(deck, message.format)
                if (!result.valid) player.ready = false
            }
            broadcastState(current)
        }
    }

    private fun handleSetPublic(session: WebSocketSession, message: ClientMessage.SetQuickGameLobbyPublic) {
        val playerSession = sessionRegistry.getPlayerSession(session.id) ?: run {
            sender.sendError(session, ErrorCode.NOT_CONNECTED, "Not connected"); return
        }
        val lobby = lobbyRepository.findContainingPlayer(playerSession.playerId) ?: run {
            sender.sendError(session, ErrorCode.GAME_NOT_FOUND, "Not in a lobby"); return
        }
        lobbyRepository.withLock(lobby.lobbyId) { current ->
            if (current == null) return@withLock
            // Host-only: first non-AI player is the host (matches the leave/close convention).
            val host = current.players.firstOrNull { !it.isAi }
            if (host?.playerId != playerSession.playerId) {
                sender.sendError(session, ErrorCode.INVALID_ACTION, "Only the host can change visibility")
                return@withLock
            }
            // AI lobbies are single-player — there's no second seat to discover.
            val effective = message.isPublic && !current.vsAi
            if (current.isPublic == effective) return@withLock
            current.isPublic = effective
            broadcastState(current)
        }
    }

    private fun handleSetSetCode(session: WebSocketSession, message: ClientMessage.SetQuickGameLobbySetCode) {
        val playerSession = sessionRegistry.getPlayerSession(session.id) ?: run {
            sender.sendError(session, ErrorCode.NOT_CONNECTED, "Not connected"); return
        }
        val lobby = lobbyRepository.findContainingPlayer(playerSession.playerId) ?: run {
            sender.sendError(session, ErrorCode.GAME_NOT_FOUND, "Not in a lobby"); return
        }
        lobbyRepository.withLock(lobby.lobbyId) { current ->
            if (current == null) return@withLock
            val player = current.findPlayer(playerSession.playerId) ?: return@withLock
            if (player.setCode == message.setCode) return@withLock
            player.setCode = message.setCode
            broadcastState(current)
        }
    }

    private fun handleCreate(session: WebSocketSession, message: ClientMessage.CreateQuickGameLobby) {
        val playerSession = sessionRegistry.getPlayerSession(session.id) ?: run {
            sender.sendError(session, ErrorCode.NOT_CONNECTED, "Not connected"); return
        }
        if (lobbyRepository.findContainingPlayer(playerSession.playerId) != null) {
            sender.sendError(session, ErrorCode.INVALID_ACTION, "Already in a lobby")
            return
        }
        if (message.vsAi && !aiGameManager.isEnabled) {
            sender.sendError(session, ErrorCode.INVALID_ACTION, "AI opponent is not enabled on this server")
            return
        }

        val lobby = QuickGameLobby(
            vsAi = message.vsAi,
            setCode = message.setCode,
            aiDeckList = message.aiDeckList,
            // AI lobbies are single-player — never publicly listed.
            isPublic = message.isPublic && !message.vsAi,
            format = message.format
        )
        lobby.players += QuickGameLobbyPlayer(
            playerId = playerSession.playerId,
            playerName = playerSession.playerName
        )
        if (message.vsAi) {
            lobby.players += QuickGameLobbyPlayer(
                playerId = com.wingedsheep.sdk.model.EntityId("ai-pending-${lobby.lobbyId}"),
                playerName = "AI Opponent",
                isAi = true,
                ready = true,
                // Use the specified AI deck list, or empty map for random deck
                deckList = message.aiDeckList ?: emptyMap()
            )
        }
        lobbyRepository.save(lobby)
        markPlayerInLobby(playerSession.playerId, lobby.lobbyId)

        logger.info("Quick game lobby ${lobby.lobbyId} created by ${playerSession.playerName} (vsAi=${message.vsAi})")
        broadcastState(lobby)
    }

    private fun handleJoin(session: WebSocketSession, message: ClientMessage.JoinQuickGameLobby) {
        val playerSession = sessionRegistry.getPlayerSession(session.id) ?: run {
            sender.sendError(session, ErrorCode.NOT_CONNECTED, "Not connected"); return
        }

        // Unified join: if the code points at a tournament lobby instead of a quick-game lobby,
        // delegate to the tournament join handler. The home-screen "Join" field doesn't need to
        // know which kind of lobby is behind a given code.
        if (lobbyRepository.findById(message.lobbyId) == null
            && tournamentLobbyRepository.findLobbyById(message.lobbyId) != null) {
            joinTournamentLobbyCallback?.invoke(session, ClientMessage.JoinLobby(message.lobbyId))
            return
        }

        lobbyRepository.withLock(message.lobbyId) { lobby ->
            if (lobby == null) {
                sender.sendError(session, ErrorCode.GAME_NOT_FOUND, "Lobby not found: ${message.lobbyId}")
                return@withLock
            }
            // Allow re-join if the player was already in this lobby (idempotent reconnect).
            val existing = lobby.findPlayer(playerSession.playerId)
            if (existing == null) {
                if (lobby.vsAi) {
                    sender.sendError(session, ErrorCode.INVALID_ACTION, "This is a single-player lobby")
                    return@withLock
                }
                if (lobby.isFull) {
                    sender.sendError(session, ErrorCode.GAME_FULL, "Lobby is full")
                    return@withLock
                }
                lobby.players += QuickGameLobbyPlayer(
                    playerId = playerSession.playerId,
                    playerName = playerSession.playerName
                )
            }
            markPlayerInLobby(playerSession.playerId, lobby.lobbyId)
            broadcastState(lobby)
        }
    }

    private fun handleLeave(session: WebSocketSession) {
        val playerSession = sessionRegistry.getPlayerSession(session.id) ?: return
        val lobby = lobbyRepository.findContainingPlayer(playerSession.playerId) ?: return

        lobbyRepository.withLock(lobby.lobbyId) { current ->
            if (current == null) return@withLock
            // Host (first non-AI player) leaving closes the lobby for everyone.
            val firstHuman = current.players.firstOrNull { !it.isAi }
            if (firstHuman?.playerId == playerSession.playerId) {
                logger.info("Host ${playerSession.playerName} left lobby ${current.lobbyId}; closing for all")
                current.players.forEach { clearPlayerLobbyMembership(it.playerId, current.lobbyId) }
                broadcastClosed(current, "Host left the lobby")
                lobbyRepository.remove(current.lobbyId)
            } else {
                current.players.removeIf { it.playerId == playerSession.playerId }
                clearPlayerLobbyMembership(playerSession.playerId, current.lobbyId)
                broadcastState(current)
            }
        }
    }

    private fun markPlayerInLobby(playerId: com.wingedsheep.sdk.model.EntityId, lobbyId: String) {
        sessionRegistry.getAllIdentities()
            .firstOrNull { it.playerId == playerId }
            ?.let { it.currentQuickGameLobbyId = lobbyId }
    }

    private fun clearPlayerLobbyMembership(playerId: com.wingedsheep.sdk.model.EntityId, lobbyId: String) {
        sessionRegistry.getAllIdentities()
            .firstOrNull { it.playerId == playerId && it.currentQuickGameLobbyId == lobbyId }
            ?.let { it.currentQuickGameLobbyId = null }
    }

    private fun handleSubmitDeck(session: WebSocketSession, message: ClientMessage.SubmitQuickGameLobbyDeck) {
        val playerSession = sessionRegistry.getPlayerSession(session.id) ?: run {
            sender.sendError(session, ErrorCode.NOT_CONNECTED, "Not connected"); return
        }
        val lobby = lobbyRepository.findContainingPlayer(playerSession.playerId) ?: run {
            sender.sendError(session, ErrorCode.GAME_NOT_FOUND, "Not in a lobby"); return
        }
        // Empty deck = "random pool" — skip validation. The commander field, if any, is meaningless
        // without an accompanying deck list and gets dropped at the lobby layer below.
        if (message.deckList.isNotEmpty()) {
            // Commander-aware path: the wire format `deckList` represents the *full* deck (matches
            // the saved-deck "merged" view the picker emits), but `Deck.cards` follows the server
            // convention where the commander lives in `Deck.commander` and is NOT counted in
            // `cards`. Strip one copy here so the validator (which re-adds it) doesn't trip the
            // singleton check on the commander itself.
            val result = if (message.commander != null) {
                val cardsWithoutCommander = stripCommanderFromCards(message.deckList, message.commander)
                val deckCards = cardsWithoutCommander.flatMap { (name, count) -> List(count) { name } }
                val richEntries = message.cardEntries
                    ?.filterNot { it.name == message.commander && it.printing == message.commanderPrinting }
                    ?.takeIf { it.isNotEmpty() }
                val deck = if (richEntries != null) {
                    com.wingedsheep.sdk.model.Deck.fromEntries(
                        entries = richEntries.map { com.wingedsheep.sdk.model.CardEntry(it.name, it.printing) },
                        commander = message.commander,
                        commanderPrinting = message.commanderPrinting,
                    )
                } else {
                    com.wingedsheep.sdk.model.Deck(
                        cards = deckCards,
                        commander = message.commander,
                        commanderPrinting = message.commanderPrinting,
                    )
                }
                deckValidator.validate(deck, lobby.format)
            } else {
                deckValidator.validate(
                    deckList = message.deckList,
                    format = lobby.format,
                    cardEntries = message.cardEntries,
                )
            }
            if (!result.valid) {
                sender.sendError(
                    session,
                    ErrorCode.INVALID_DECK,
                    result.errors.firstOrNull()?.message ?: "Invalid deck"
                )
                return
            }
        }
        lobbyRepository.withLock(lobby.lobbyId) { current ->
            if (current == null) return@withLock
            val player = current.findPlayer(playerSession.playerId) ?: return@withLock
            // No-op if the same deck is being resubmitted: avoids ping-pong with the picker
            // (which can re-emit its current value on every render) and keeps the ready flag
            // sticky as long as the player's chosen deck hasn't actually changed.
            if (player.deckList == message.deckList && player.commander == message.commander) return@withLock
            player.deckList = message.deckList
            // For random-pool submissions the commander field is meaningless; drop it so a stale
            // commander from a prior submission doesn't leak into the game start.
            player.commander = if (message.deckList.isNotEmpty()) message.commander else null
            // Submitting a *new* deck un-readies the player so they have to confirm again.
            player.ready = false
            broadcastState(current)
        }
    }

    private fun handleSetReady(session: WebSocketSession, message: ClientMessage.SetQuickGameLobbyReady) {
        val playerSession = sessionRegistry.getPlayerSession(session.id) ?: run {
            sender.sendError(session, ErrorCode.NOT_CONNECTED, "Not connected"); return
        }
        val lobby = lobbyRepository.findContainingPlayer(playerSession.playerId) ?: run {
            sender.sendError(session, ErrorCode.GAME_NOT_FOUND, "Not in a lobby"); return
        }

        lobbyRepository.withLock(lobby.lobbyId) { current ->
            if (current == null) return@withLock
            val player = current.findPlayer(playerSession.playerId) ?: return@withLock
            // Allow ready-up only if a deck has been submitted (null = nothing chosen yet).
            if (message.ready && player.deckList == null) {
                sender.sendError(session, ErrorCode.INVALID_ACTION, "Pick a deck before readying up")
                return@withLock
            }
            player.ready = message.ready
            broadcastState(current)
            if (current.allReady() && !current.started) {
                current.started = true
                startGame(current)
            }
        }
    }

    private fun startGame(lobby: QuickGameLobby) {
        // Commander-shape lobby with a human player who never designated a commander would crash
        // mid-init when GameInitializer requires `commanderCardName`. Surface the error early so
        // the player can pick a different deck before everyone gets disconnected.
        if (lobby.format?.isCommanderShape == true) {
            val missing = lobby.players.firstOrNull { !it.isAi && it.deckList?.isNotEmpty() == true && it.commander.isNullOrBlank() }
            if (missing != null) {
                logger.warn("Lobby ${lobby.lobbyId}: human player ${missing.playerName} has no commander designated for ${lobby.format} game start")
                broadcastClosed(
                    lobby,
                    "${missing.playerName}'s deck has no commander designated — pick a deck with a commander to play ${lobby.format!!.displayName}",
                )
                lobbyRepository.remove(lobby.lobbyId)
                return
            }
        }

        val gameSession = GameSession(
            cardRegistry = cardRegistry,
            useHandSmoother = gameProperties.handSmoother.enabled,
            debugMode = gameProperties.debugMode,
            printingRegistry = printingRegistry,
        )
        // Commander-shape formats (Commander / Brawl / Standard Brawl) run on the engine's 1v1
        // Commander rules. Other formats fall through to Standard. Brawl-specific tweaks
        // (60 cards, alternative life total) are Phase 4 territory — Phase 1 covers Commander.
        if (lobby.format?.isCommanderShape == true) {
            gameSession.engineFormat = com.wingedsheep.sdk.core.Format.Commander()
        }
        // Each player can pick their own set for a Random pool; the AI uses the lobby-level
        // setCode (or any human player's choice as a default) when its random deck is generated.
        val aiSetCode = lobby.setCode
            ?: lobby.players.firstNotNullOfOrNull { it.setCode }
            ?: deckGenerator.randomSetCode()
        gameSession.quickGameSetCode = aiSetCode
        gameSession.publicSpectate = lobby.isPublic && !lobby.vsAi

        val humanPlayers = lobby.players.filter { !it.isAi }
        for (lobbyPlayer in humanPlayers) {
            val deckList = resolveDeck(lobbyPlayer)
            val playerSession = sessionRegistry
                .getAllIdentities()
                .firstOrNull { it.playerId == lobbyPlayer.playerId }
                ?.let { identity ->
                    val ws = identity.webSocketSession ?: return@let null
                    sessionRegistry.getPlayerSession(ws.id)
                }
            if (playerSession == null) {
                logger.error("Lobby ${lobby.lobbyId}: lost session for ${lobbyPlayer.playerName} on game start")
                broadcastClosed(lobby, "A player disconnected before the game started")
                lobbyRepository.remove(lobby.lobbyId)
                return
            }
            // Pass the commander only for commander-shape formats; clear it otherwise so a stale
            // commander on a saved deck doesn't accidentally route into a Standard game. Strip
            // one copy of the commander out of the wire deck list so the engine sees `cards`
            // (= library) excluding the commander, matching `Deck.cards` convention.
            val commander = if (lobby.format?.isCommanderShape == true) lobbyPlayer.commander else null
            val engineDeckList = if (commander != null) {
                stripCommanderFromCards(deckList, commander)
            } else {
                deckList
            }
            gameSession.addPlayer(playerSession, engineDeckList, commanderCardName = commander)
            // Persistence info so a mid-game reconnect can find the player by token.
            val token = sessionRegistry.getTokenByWsId(playerSession.webSocketSession.id)
            if (token != null) {
                gameSession.setPlayerPersistenceInfo(playerSession.playerId, playerSession.playerName, token)
                sessionRegistry.getIdentityByToken(token)?.currentGameSessionId = gameSession.sessionId
            }
        }

        gameRepository.save(gameSession)

        if (lobby.vsAi) {
            // Get AI deck list from lobby if specified, otherwise use random deck
            val aiDeckList = lobby.aiDeckList
            aiGameManager.createAiOpponent(
                gameSession = gameSession,
                setCode = aiSetCode,
                deckList = aiDeckList,
                onActionReady = { id, action -> gamePlayHandler.handleAiAction(gameSession, id, action) },
                onMulliganKeep = { id -> gamePlayHandler.handleAiMulliganKeep(gameSession, id) },
                onMulliganTake = { id -> gamePlayHandler.handleAiMulliganTake(gameSession, id) },
                onBottomCards = { id, cardIds -> gamePlayHandler.handleAiBottomCards(gameSession, id, cardIds) }
            )
        }

        // Tell players the game has been created (so the client can switch overlays) before mulligan kicks in.
        for (lobbyPlayer in humanPlayers) {
            val ws = sessionRegistry
                .getAllIdentities()
                .firstOrNull { it.playerId == lobbyPlayer.playerId }
                ?.webSocketSession ?: continue
            sender.send(ws, ServerMessage.GameCreated(gameSession.sessionId))
        }

        gamePlayHandler.startGame(gameSession)
        logger.info("Quick lobby ${lobby.lobbyId} → game ${gameSession.sessionId} started")
        // The lobby has done its job; remove it so the same player can create another later.
        lobby.players.forEach { clearPlayerLobbyMembership(it.playerId, lobby.lobbyId) }
        lobbyRepository.remove(lobby.lobbyId)
    }

    /**
     * Re-send the current lobby state to a reconnecting player. Called by [ConnectionHandler]
     * after a successful reconnect, when the player's identity still has a `currentQuickGameLobbyId`.
     */
    fun handleReconnect(session: WebSocketSession, playerId: com.wingedsheep.sdk.model.EntityId, lobbyId: String) {
        val lobby = lobbyRepository.findById(lobbyId)
        if (lobby == null) {
            // Lobby is gone (host left, server restarted, etc.) — clear stale identity pointer
            // and send a closed-message so the client can fall back to the home screen cleanly.
            sessionRegistry.getAllIdentities()
                .firstOrNull { it.playerId == playerId && it.currentQuickGameLobbyId == lobbyId }
                ?.let { it.currentQuickGameLobbyId = null }
            sender.send(session, ServerMessage.QuickGameLobbyClosed("Lobby no longer exists"))
            return
        }
        // Send just this player the current snapshot.
        val msg = ServerMessage.QuickGameLobbyState(
            lobbyId = lobby.lobbyId,
            vsAi = lobby.vsAi,
            setCode = lobby.setCode,
            players = lobby.players.map { it.toView() },
            youPlayerId = playerId,
            canStart = lobby.allReady(),
            isPublic = lobby.isPublic,
            format = lobby.format
        )
        sender.send(session, msg)
    }

    private fun resolveDeck(player: QuickGameLobbyPlayer): Map<String, Int> {
        val submitted = player.deckList ?: emptyMap()
        if (submitted.isEmpty()) {
            // Player chose Random — honor their per-player set choice; fall back to a random set.
            val setCode = player.setCode ?: deckGenerator.randomSetCode()
            return deckGenerator.generate(setCode)
        }
        return submitted
    }

    private fun broadcastState(lobby: QuickGameLobby) {
        for (player in lobby.players) {
            if (player.isAi) continue
            val ws = sessionRegistry
                .getAllIdentities()
                .firstOrNull { it.playerId == player.playerId }
                ?.webSocketSession ?: continue
            val msg = ServerMessage.QuickGameLobbyState(
                lobbyId = lobby.lobbyId,
                vsAi = lobby.vsAi,
                setCode = lobby.setCode,
                players = lobby.players.map { it.toView() },
                youPlayerId = player.playerId,
                canStart = lobby.allReady(),
                isPublic = lobby.isPublic,
                format = lobby.format
            )
            sender.send(ws, msg)
        }
    }

    /**
     * Subtract one copy of [commander] from [deckList]. Mirrors the web-client's
     * `stripCommanderFromCards` helper — the wire format ships the merged deck (commander
     * counted in `deckList`), but the server's `Deck.cards` convention excludes it. Idempotent
     * when the commander isn't present.
     */
    private fun stripCommanderFromCards(
        deckList: Map<String, Int>,
        commander: String,
    ): Map<String, Int> {
        val current = deckList[commander] ?: return deckList
        val next = deckList.toMutableMap()
        if (current <= 1) next.remove(commander) else next[commander] = current - 1
        return next
    }

    private fun broadcastClosed(lobby: QuickGameLobby, reason: String) {
        for (player in lobby.players) {
            if (player.isAi) continue
            val ws = sessionRegistry
                .getAllIdentities()
                .firstOrNull { it.playerId == player.playerId }
                ?.webSocketSession ?: continue
            sender.send(ws, ServerMessage.QuickGameLobbyClosed(reason))
        }
    }

    private fun QuickGameLobbyPlayer.toView(): ServerMessage.QuickGameLobbyPlayerView {
        val total = deckList?.values?.sum() ?: 0
        val label = when {
            deckList == null -> "Choosing…"
            deckList!!.isEmpty() -> if (setCode != null) "Random Pool ($setCode)" else "Random Pool"
            else -> "Custom ($total)"
        }
        return ServerMessage.QuickGameLobbyPlayerView(
            playerId = playerId,
            playerName = playerName,
            isAi = isAi,
            ready = ready,
            deckSelected = deckList != null,
            deckCardCount = total,
            deckLabel = label,
            setCode = setCode
        )
    }

    private fun handleSetAiDeck(session: WebSocketSession, message: ClientMessage.SetQuickGameLobbyAiDeck) {
        val playerSession = sessionRegistry.getPlayerSession(session.id) ?: run {
            sender.sendError(session, ErrorCode.NOT_CONNECTED, "Not connected")
            return
        }

        val lobby = lobbyRepository.findContainingPlayer(playerSession.playerId) ?: run {
            sender.sendError(session, ErrorCode.INVALID_ACTION, "Not in a lobby")
            return
        }

        // Only allow AI deck changes in AI lobbies
        if (!lobby.vsAi) {
            sender.sendError(session, ErrorCode.INVALID_ACTION, "AI deck can only be set in AI lobbies")
            return
        }

        // Only the host (first non-AI player) can set the AI deck
        val host = lobby.players.find { !it.isAi } ?: run {
            sender.sendError(session, ErrorCode.INVALID_ACTION, "No host found")
            return
        }

        if (host.playerId != playerSession.playerId) {
            sender.sendError(session, ErrorCode.INVALID_ACTION, "Only the host can set the AI deck")
            return
        }

        // Update the AI deck in the lobby
        lobby.aiDeckList = message.aiDeckList
        lobbyRepository.save(lobby)

        // Update the AI player's deck list
        val aiPlayer = lobby.players.find { it.isAi } ?: run {
            sender.sendError(session, ErrorCode.INVALID_ACTION, "No AI player found")
            return
        }

        aiPlayer.deckList = message.aiDeckList

        // Broadcast the updated lobby state
        broadcastState(lobby)
    }
}
