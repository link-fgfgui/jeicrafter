package com.jeicrafter.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jeicrafter.Constants;
import com.jeicrafter.platform.Services;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lightweight JSON config stored in the platform config directory.
 * All values are toggled by the user; defaults enable the workstation features.
 */
public final class JeiCrafterConfig {
	private static final String FILE_NAME = Constants.MOD_ID + ".json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Default search radius for auto-opening a workstation: the vanilla server's block interaction limit (6.0 from the eye). */
	public static final double DEFAULT_WORKSTATION_MAX_DISTANCE = 6.0D;
	public static final double DEFAULT_MIN_HIGHLIGHT_DISTANCE = 16.0D;

	private static boolean enableWorkstationHighlight = true;
	private static boolean closeGuiOnWorkstationHighlight = true;
	private static boolean autoOpenWorkstation = true;
	private static boolean autoTransferItems = true;
	private static double workstationMaxDistance = DEFAULT_WORKSTATION_MAX_DISTANCE;
	private static double minHighlightDistance = DEFAULT_MIN_HIGHLIGHT_DISTANCE;

	private JeiCrafterConfig() {
	}

	/** Loads (or creates with defaults) the config file. Call once during client init. */
	public static void load() {
		Path configFile = Services.PLATFORM.getConfigDir().resolve(FILE_NAME);
		if (Files.isRegularFile(configFile)) {
			try (Reader reader = Files.newBufferedReader(configFile)) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				enableWorkstationHighlight = getBoolean(json, "enableWorkstationHighlight", enableWorkstationHighlight);
				closeGuiOnWorkstationHighlight = getBoolean(json, "closeGuiOnWorkstationHighlight", closeGuiOnWorkstationHighlight);
				autoOpenWorkstation = getBoolean(json, "autoOpenWorkstation", autoOpenWorkstation);
				autoTransferItems = getBoolean(json, "autoTransferItems", autoTransferItems);
				workstationMaxDistance = getDouble(json, "workstationMaxDistance", workstationMaxDistance);
				minHighlightDistance = getDouble(json, "minHighlightDistance", minHighlightDistance);
			} catch (Exception exception) {
				Constants.LOG.error("[JeiCrafter] Failed to read config file {}", configFile, exception);
			}
		} else {
			save();
		}
	}

	/** Writes the current values to the config file. */
	public static void save() {
		Path configFile = Services.PLATFORM.getConfigDir().resolve(FILE_NAME);
		try {
			Files.createDirectories(configFile.getParent());
			try (Writer writer = Files.newBufferedWriter(configFile)) {
				JsonObject json = new JsonObject();
				json.addProperty("enableWorkstationHighlight", enableWorkstationHighlight);
				json.addProperty("closeGuiOnWorkstationHighlight", closeGuiOnWorkstationHighlight);
				json.addProperty("autoOpenWorkstation", autoOpenWorkstation);
				json.addProperty("autoTransferItems", autoTransferItems);
				json.addProperty("workstationMaxDistance", workstationMaxDistance);
				json.addProperty("minHighlightDistance", minHighlightDistance);
				GSON.toJson(json, writer);
			}
		} catch (IOException exception) {
			Constants.LOG.error("[JeiCrafter] Failed to write config file {}", configFile, exception);
		}
	}

	/** Whether the recipe-workstation highlight feature is enabled. */
	public static boolean enableWorkstationHighlight() {
		return enableWorkstationHighlight;
	}

	/** Whether triggering workstation highlight automatically closes the recipe GUI. */
	public static boolean closeGuiOnWorkstationHighlight() {
		return closeGuiOnWorkstationHighlight;
	}

	/** Whether auto-craft should automatically open the nearest matching workstation GUI. */
	public static boolean autoOpenWorkstation() {
		return autoOpenWorkstation;
	}

	/**
	 * Whether the workstation action should insert the recipe's required items: when the
	 * workstation is opened for the player (the not-open branch) and when a workstation is
	 * already open / a valid transfer handler exists. With this disabled the action only opens
	 * (or acknowledges) the workstation and stops without inserting anything.
	 */
	public static boolean autoTransferItems() {
		return autoTransferItems;
	}

	/**
	 * Max distance (blocks) to search for a matching workstation when auto-opening one.
	 * Defaults to the vanilla server's block interaction limit; anything beyond it can never
	 * be opened by the server, so raising the value only widens a useless search.
	 */
	public static double workstationMaxDistance() {
		return workstationMaxDistance;
	}

	/**
	 * Reference distance (in blocks) at which a 1x1 block's apparent screen size becomes the
	 * minimum allowed highlight size. Beyond this distance, highlights scale up proportionally
	 * with distance so they never appear smaller on screen. Set to 0 to disable scaling.
	 */
	public static double minHighlightDistance() {
		return minHighlightDistance;
	}

	private static boolean getBoolean(JsonObject json, String key, boolean defaultValue) {
		return json.has(key) ? json.get(key).getAsBoolean() : defaultValue;
	}

	private static double getDouble(JsonObject json, String key, double defaultValue) {
		return json.has(key) ? json.get(key).getAsDouble() : defaultValue;
	}
}
