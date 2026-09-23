package com.hardcoreseamless.deathsignal;

import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SENSOR + LOCAL RUN-LOSS GUARD. Detects a real {@link ServerPlayer} death via Fabric API's
 * {@code ServerLivingEntityEvents.AFTER_DEATH} (fires once, after the death has actually
 * happened - not the cancelable ALLOW_DEATH check), writes one event file to the local spool, and
 * (FASE 8) locally contains this JVM once a run-ending death has happened on it, so no player can
 * keep playing a run that has already been declared lost while the standby backend is prepared.
 *
 * <p>Still deliberately does nothing about switch, recycle, or Velocity itself - that remains
 * HardcoreCoordinator's job (see velocity-plugin/hardcore-coordinator). See FASE 7 spec section 5
 * and FASE 8 spec sections 14-16 for the strict separation this enforces: containment here is
 * purely local (in-memory {@code localRunEnded}, never persisted), and dies with the JVM when this
 * backend is stopped and recycled - a fresh JVM always starts with {@code localRunEnded=false}.
 *
 * <p>Built against {@code loom.officialMojangMappings()} - class/method names here are Mojang's
 * own (e.g. {@code ServerPlayer}, {@code ServerLevel}, {@code getMsgId()}), verified via javap
 * against the actual remapped Minecraft jar, not assumed from Yarn-mapping tutorials.
 */
public final class HardcoreDeathSignalMod implements ModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("HardcoreDeathSignal");
    private static final String DEFAULT_SPOOL_DIR = "E:\\minecraft-hardcore\\runtime\\death-events";

    private DeathEventWriter writer;
    private String backendId;

    // Local-only, in-memory, never persisted to disk - dies with this JVM (FASE 8 spec section 16).
    private volatile boolean localRunEnded = false;

    @Override
    public void onInitialize() {
        backendId = System.getProperty("hardcore.backendId");
        if (backendId == null || backendId.isBlank()) {
            LOGGER.error("[HardcoreDeathSignal] hardcore.backendId system property not set - "
                    + "mod disabled, no death events will be written.");
            return;
        }

        String spoolDirProp = System.getProperty("hardcore.deathSpoolDir", DEFAULT_SPOOL_DIR);
        try {
            writer = new DeathEventWriter(Path.of(spoolDirProp));
        } catch (IOException e) {
            LOGGER.error("[HardcoreDeathSignal] Failed to initialize spool directory {} - mod disabled.",
                    spoolDirProp, e);
            return;
        }

        LOGGER.info("[HardcoreDeathSignal] LOADED backend={} spoolDir={}", backendId, spoolDirProp);

        ServerLivingEntityEvents.AFTER_DEATH.register(this::onAfterDeath);
        ServerPlayerEvents.AFTER_RESPAWN.register(this::onAfterRespawn);
        ServerPlayConnectionEvents.JOIN.register(this::onPlayerJoin);
    }

    private void onAfterDeath(LivingEntity entity, DamageSource damageSource) {
        // Filter strictly to real players - never zombies, animals, armor stands, etc.
        if (!(entity instanceof ServerPlayer player)) {
            return;
        }

        // Contain this JVM on the FIRST run-ending death, regardless of whether the event file
        // write below succeeds - the run is lost the moment a real player dies, independent of
        // HardcoreCoordinator ever seeing it (once-only: no duplicate log/containment spam for a
        // second death on an already-lost backend, see FASE 8 spec section 21).
        if (!localRunEnded) {
            localRunEnded = true;
            LOGGER.info("[HardcoreDeathSignal] LOCAL_RUN_ENDED backend={} deathPlayer={}",
                    backendId, player.getName().getString());
            containRun(player.getServer());
        }

        if (writer == null) {
            return;
        }

        ServerLevel level = player.serverLevel();
        DeathEvent event = new DeathEvent(
                backendId,
                System.currentTimeMillis(),
                player.getStringUUID(),
                player.getName().getString(),
                level.dimension().location().toString(),
                player.getX(),
                player.getY(),
                player.getZ(),
                damageSource.getMsgId(),
                level.getSeed()
        );

        try {
            String eventId = writer.write(event);
            LOGGER.info("[HardcoreDeathSignal] DEATH_EVENT_WRITTEN eventId={} backend={} player={} cause={}",
                    eventId, backendId, event.playerName(), event.damageSource());
        } catch (IOException e) {
            LOGGER.error("[HardcoreDeathSignal] DEATH_EVENT_WRITE_FAILED backend={} player={}",
                    backendId, event.playerName(), e);
        }
    }

    // Forces every currently-connected player on this backend into SPECTATOR the moment the run
    // is declared lost - not just the player who died. GLOBAL RUN LOST means nobody on the losing
    // backend keeps playing it, not only the one whose death caused it (FASE 8 spec section 19).
    private void containRun(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.isSpectator()) {
                p.setGameMode(GameType.SPECTATOR);
            }
        }
    }

    // Vanilla respawn would otherwise put the player back into a normal playable gamemode on this
    // same lost world. If this backend's run is already over, re-lock them into SPECTATOR instead
    // of letting the respawn screen hand back real gameplay (FASE 8 spec section 18).
    private void onAfterRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive) {
        if (localRunEnded && !newPlayer.isSpectator()) {
            newPlayer.setGameMode(GameType.SPECTATOR);
        }
    }

    // Defensive: if a player connects to this backend after its run was already declared lost
    // (e.g. briefly during the WAITING_FOR_STANDBY window), never hand them normal gameplay here.
    private void onPlayerJoin(ServerGamePacketListenerImpl handler, PacketSender sender, MinecraftServer server) {
        if (!localRunEnded) {
            return;
        }
        ServerPlayer player = handler.getPlayer();
        if (player != null && !player.isSpectator()) {
            player.setGameMode(GameType.SPECTATOR);
        }
    }
}
