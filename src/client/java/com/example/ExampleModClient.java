package com.example;

import com.example.autofisher.config.AutoFisherConfig;
import com.example.mixin.client.PlayerInventoryMixin;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ExampleModClient implements ClientModInitializer {

	private static final String FISHING_HOLOGRAM_NAME = "!!!";
	private static final long DETECTION_COOLDOWN_MS = 1000;
	private static final ScheduledExecutorService SCHEDULER = Executors.newScheduledThreadPool(1);
	private static final Random RANDOM = new Random();

	private long lastDetectionTime = 0;
	private static int catchCount = 0; // Counter to trigger Fireveil every 2nd catch
	private static boolean isFireveilRunning = false;
	private static ScheduledFuture<?> recastTask;

	@Override
	public void onInitializeClient() {
		AutoFisherConfig.load();

		// Register all client-side commands
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> registerCommands(dispatcher));
		
		// Listen to client ticks to detect the fishing hologram
		ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
	}

	private void onClientTick(Minecraft client) {
		if (!AutoFisherConfig.enabled || client.level == null || client.player == null) return;

		long currentTime = System.currentTimeMillis();
		if (currentTime - lastDetectionTime <= DETECTION_COOLDOWN_MS) return;

		for (Entity entity : client.level.entitiesForRendering()) {
			if (entity instanceof ArmorStand armorStand) {
				if (armorStand.hasCustomName() && armorStand.getCustomName().getString().equals(FISHING_HOLOGRAM_NAME)) {
					
					// Prevent reeling in while the Fireveil sequence is actively swapping items
					if (isFireveilRunning) return;

					lastDetectionTime = currentTime;
					executeCatchSequence(client);
					break; 
				}
			}
		}
	}

	private void executeCatchSequence(Minecraft client) {
		// Simulate human reaction time before reeling in the fish (56-131 ms)
		int clickDelay = 56 + RANDOM.nextInt(37) + RANDOM.nextInt(38);
		
		debugLog(client, "§7[AutoFisher] §fHologram detected! Reeling in in " + clickDelay + "ms...");

		scheduleOnMainThread(clickDelay, () -> {
			// Reel in the fish (Right Click)
			client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);

			catchCount++;

			// Triggers Fireveil exactly every 2nd catch. 
			// This replaces the old unreliable chat-parsing logic and perfectly balances 
			// maximum aura uptime with optimal mana conservation.
			if (AutoFisherConfig.fireveilEnabled && catchCount >= 2) {
				catchCount = 0; // Reset counter
				triggerFireveilSequence(client);
			} else if (AutoFisherConfig.enableRecast) {
				scheduleRecast(client);
			}
		});
	}

	private static void triggerFireveilSequence(Minecraft client) {
		isFireveilRunning = true;

		// Cancel any previously scheduled recast to prevent the rod from casting during the Fireveil sequence
		if (AutoFisherConfig.enableRecast && recastTask != null && !recastTask.isDone()) {
			recastTask.cancel(false);
		}

		PlayerInventoryMixin inventoryAccessor = (PlayerInventoryMixin) client.player.getInventory();
		final int originalSlot = inventoryAccessor.getSelectedSlot();

		// HUMANIZED DELAYS: 
		// Math.max sets a strict minimum human reaction time to prevent Anti-Cheat flags.
		// The RANDOM value is always added ON TOP of the base to guarantee variance and avoid identical sequential delays.
		int baseSlot = Math.max(150, AutoFisherConfig.fireveilDelayToSlot2_base);
		int randSlot = RANDOM.nextInt(Math.max(50, AutoFisherConfig.fireveilDelayToSlot2_random));
		int delayToSlot2 = baseSlot + randSlot;

		int baseClick = Math.max(200, AutoFisherConfig.fireveilDelayToRightClick_base);
		int randClick = RANDOM.nextInt(Math.max(60, AutoFisherConfig.fireveilDelayToRightClick_random));
		int delayToRightClick = baseClick + randClick;

		int baseBack = Math.max(200, AutoFisherConfig.fireveilDelayToOriginalSlot_base);
		int randBack = RANDOM.nextInt(Math.max(50, AutoFisherConfig.fireveilDelayToOriginalSlot_random));
		int delayToOriginalSlot = baseBack + randBack;

		debugLog(client, String.format("§c[AutoFisher] §6Using Fireveil (delays: slot=%dms, click=%dms, back=%dms)...", delayToSlot2, delayToRightClick, delayToOriginalSlot));

		// Step 1: Switch to the Fireveil Wand slot
		scheduleOnMainThread(delayToSlot2, () -> {
			inventoryAccessor.setSelectedSlot(1); 
			// NOTE: Do NOT send ServerboundSetCarriedItemPacket manually here. 
			// Vanilla handles slot sync automatically. Sending it manually causes duplicate packets and Hypixel kicks.

			// Step 2: Right click with the Wand
			scheduleOnMainThread(delayToRightClick, () -> {
				client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);

				// Step 3: Switch back to the fishing rod
				scheduleOnMainThread(delayToOriginalSlot, () -> {
					inventoryAccessor.setSelectedSlot(originalSlot);
					isFireveilRunning = false; // Sequence complete, release lock

					// Step 4: Cast the fishing rod back into the water
					if (AutoFisherConfig.enableRecast) {
						scheduleRecast(client);
					}
				});
			});
		});
	}

	private static void scheduleRecast(Minecraft client) {
		// Gaussian distribution for recast delay (bell curve) to simulate human inconsistency
		int finalRecastDelay = 390 + (RANDOM.nextInt(200) + RANDOM.nextInt(200) + RANDOM.nextInt(200)) % (980 - 390 + 1);

		debugLog(client, "§7[AutoFisher] §bScheduling recast in " + finalRecastDelay + "ms...");

		recastTask = SCHEDULER.schedule(() -> {
			Minecraft.getInstance().execute(() -> {
				if (Minecraft.getInstance().gameMode != null && Minecraft.getInstance().player != null) {
					Minecraft.getInstance().gameMode.useItem(Minecraft.getInstance().player, InteractionHand.MAIN_HAND);
				}
			});
		}, finalRecastDelay, TimeUnit.MILLISECONDS);
	}

	/**
	 * Helper method to schedule tasks on the main Minecraft client thread.
	 * This flattens the async code structure and eliminates "Callback Hell".
	 */
	private static void scheduleOnMainThread(int delayMs, Runnable action) {
		SCHEDULER.schedule(() -> {
			Minecraft client = Minecraft.getInstance();
			client.execute(() -> {
				if (client.player != null && client.gameMode != null && client.getConnection() != null) {
					action.run();
				} else {
					isFireveilRunning = false; // Safe reset if the player disconnects or world unloads
				}
			});
		}, delayMs, TimeUnit.MILLISECONDS);
	}

	private static void debugLog(Minecraft client, String message) {
		if (AutoFisherConfig.debugMode && client.player != null) {
			client.player.sendSystemMessage(Component.literal(message));
		}
	}

	private void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("autofisher")
				.executes(context -> {
					context.getSource().sendFeedback(Component.literal("AutoFisher is " + (AutoFisherConfig.enabled ? "enabled" : "disabled") + ". Recast is " + (AutoFisherConfig.enableRecast ? "enabled" : "disabled") + ". Debug mode is " + (AutoFisherConfig.debugMode ? "on" : "off") + ". Fireveil is " + (AutoFisherConfig.fireveilEnabled ? "enabled" : "disabled") + "."));
					return 1;
				})
				.then(ClientCommands.literal("on").executes(context -> toggleConfig(context.getSource(), "enabled", true)))
				.then(ClientCommands.literal("off").executes(context -> toggleConfig(context.getSource(), "enabled", false)))
				.then(ClientCommands.literal("recast")
						.then(ClientCommands.literal("on").executes(context -> toggleConfig(context.getSource(), "enableRecast", true)))
						.then(ClientCommands.literal("off").executes(context -> toggleConfig(context.getSource(), "enableRecast", false))))
				.then(ClientCommands.literal("debug")
						.then(ClientCommands.literal("on").executes(context -> toggleConfig(context.getSource(), "debugMode", true)))
						.then(ClientCommands.literal("off").executes(context -> toggleConfig(context.getSource(), "debugMode", false))))
				.then(ClientCommands.literal("fireveil")
						.then(ClientCommands.literal("on").executes(context -> toggleConfig(context.getSource(), "fireveilEnabled", true)))
						.then(ClientCommands.literal("off").executes(context -> toggleConfig(context.getSource(), "fireveilEnabled", false))))
		);
	}

	private int toggleConfig(FabricClientCommandSource source, String setting, boolean state) {
		switch (setting) {
			case "enabled" -> AutoFisherConfig.enabled = state;
			case "enableRecast" -> AutoFisherConfig.enableRecast = state;
			case "debugMode" -> AutoFisherConfig.debugMode = state;
			case "fireveilEnabled" -> AutoFisherConfig.fireveilEnabled = state;
		}
		AutoFisherConfig.save();
		source.sendFeedback(Component.literal("AutoFisher " + setting + " set to " + state + "."));
		return 1;
	}
}
