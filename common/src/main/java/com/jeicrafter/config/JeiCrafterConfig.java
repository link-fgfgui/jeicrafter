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

	private static boolean enableWorkstationHighlight = true;
	private static boolean autoOpenWorkstation = true;
	private static boolean autoTransferItems = true;
	private static boolean autoCloseWorkstation = true;
	private static int workstationMaxDistance = 64;

	private JeiCrafterConfig() {
	}

	/** Loads (or creates with defaults) the config file. Call once during client init. */
	public static void load() {
		Path configFile = Services.PLATFORM.getConfigDir().resolve(FILE_NAME);
		if (Files.isRegularFile(configFile)) {
			try (Reader reader = Files.newBufferedReader(configFile)) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				enableWorkstationHighlight = getBoolean(json, "enableWorkstationHighlight", enableWorkstationHighlight);
				autoOpenWorkstation = getBoolean(json, "autoOpenWorkstation", autoOpenWorkstation);
				autoTransferItems = getBoolean(json, "autoTransferItems", autoTransferItems);
				autoCloseWorkstation = getBoolean(json, "autoCloseWorkstation", autoCloseWorkstation);
				workstationMaxDistance = getInt(json, "workstationMaxDistance", workstationMaxDistance);
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
				json.addProperty("autoOpenWorkstation", autoOpenWorkstation);
				json.addProperty("autoTransferItems", autoTransferItems);
				json.addProperty("autoCloseWorkstation", autoCloseWorkstation);
				json.addProperty("workstationMaxDistance", workstationMaxDistance);
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

	/** Whether auto-craft should automatically open the nearest matching workstation GUI. */
	public static boolean autoOpenWorkstation() {
		return autoOpenWorkstation;
	}

	/** Whether auto-craft should transfer the recipe's required items into the workstation. */
	public static boolean autoTransferItems() {
		return autoTransferItems;
	}

	/** Whether auto-craft should close the workstation GUI after extracting the output. */
	public static boolean autoCloseWorkstation() {
		return autoCloseWorkstation;
	}

	/** Max distance to search for a matching workstation when auto-opening one. */
	public static int workstationMaxDistance() {
		return workstationMaxDistance;
	}

	private static boolean getBoolean(JsonObject json, String key, boolean defaultValue) {
		return json.has(key) ? json.get(key).getAsBoolean() : defaultValue;
	}

	private static int getInt(JsonObject json, String key, int defaultValue) {
		return json.has(key) ? json.get(key).getAsInt() : defaultValue;
	}
}
