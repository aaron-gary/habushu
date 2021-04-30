package org.bitbucket.cpointe.habushu;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;

import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.plexus.components.cipher.PlexusCipherException;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcherException;

/**
 * A util class for the habushu-maven-plugin, containing some shared logic
 * between a few other classes.
 *
 */
public final class HabushuUtil {

	private static final Logger logger = LoggerFactory.getLogger(HabushuUtil.class);
	
	private static Settings settings;

	private HabushuUtil() {}

	/**
	 * Changes a private field in the CleanMojo using reflection.
	 * 
	 * @param clazz    the class that has the field
	 * @param field    the field to change
	 * @param newValue the new value for the field
	 * @throws Exception
	 */
	public static void changePrivateCleanMojoField(Class<?> clazz, String fieldName, Object newValue) {
		try {
			Object instance = clazz.newInstance();
			Field fieldToModify = clazz.getDeclaredField(fieldName);
			fieldToModify.setAccessible(true);

			fieldToModify.set(instance, newValue);
		} catch (IllegalArgumentException | IllegalAccessException | NoSuchFieldException | SecurityException
				| InstantiationException e) {
			throw new HabushuException("Could not set value for field " + fieldName, e);
		}
	}
	
	/**
	 * Find the username for a given server in Maven's user settings.
	 * 
	 * @return the username for the server specified in Maven's settings.xml
	 */
	public static String findUsernameForServer(String serverId) {
		Server server = settings.getServer(serverId);
		
		return server != null ? server.getUsername() : null;
	}

	/**
	 * Simple utility method to decrypt a stored password for a server.
	 * 
	 * @param serverId the id of the server to decrypt the password for
	 */
	public static String decryptServerPassword(String serverId) {
		String decryptedPassword = null;
		
		MavenPasswordDecoder.setMavenSettings(settings);

		try {
			decryptedPassword = MavenPasswordDecoder.decryptPasswordForServer(serverId);
		} catch (PlexusCipherException | IOException | XmlPullParserException | SecDispatcherException e) {
			throw new HabushuException("Unable to decrypt stored passwords.", e);
		}

		return decryptedPassword;
	}
	
	/**
	 * Run the bash script found at the given location without parameters.
	 * @param bashScriptPath absolute path to the bash script
	 */
	public static void runBashScript(String bashScriptPath) {
		runBashScript(bashScriptPath, null, true);
	}

	/**
	 * Run the bash script found at the given location with the provided parameters.
	 * @param bashScriptPath absolute path to the bash script
	 * @param parameters script parameters
	 * @param debug true to log script output as DEBUG, otherwise logged as INFO
	 */
	public static void runBashScript(String bashScriptPath, String[] parameters, boolean debug) {
		logger.debug("Running bash script located at {}.", bashScriptPath);

		try {
			String[] command;
			if (parameters != null && parameters.length > 0) {
				command = new String[parameters.length + 1];

				for (int i = 0; i < parameters.length; i++) {
					command[i + 1] = parameters[i];
				}
			} else {
				command = new String[1];
			}
			command[0] = bashScriptPath;

			Process process = Runtime.getRuntime().exec(command);

			StringBuilder output = new StringBuilder();
			String line;

			BufferedReader stdInReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			while ((line = stdInReader.readLine()) != null) {
				output.append(line + "\n");
			}

			BufferedReader stdErrReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
			while ((line = stdErrReader.readLine()) != null) {
				output.append(line + "\n");
			}

			if (debug) {
			    logger.debug(output.toString());
			} else {
			    logger.info(output.toString());
			}

			int exitVal = process.waitFor();
			if (exitVal != 0) {
				throw new HabushuException("Error encountered when running bash script located at " + bashScriptPath);
			}
		} catch (IOException | InterruptedException e) {
			throw new HabushuException("Could not run bash script.", e);
		}
	}

	/**
	 * Writes a given list of lines to the file located at the provided file path.
	 * 
	 * @param commands the newline-delineated list of String file lines
	 * @param filePath the path to the file
	 */
	public static void writeLinesToFile(String commands, String filePath) {
		logger.debug("Writing lines to file located at {}.", filePath);

		try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
			writer.write(commands);
		} catch (IOException e) {
			throw new HabushuException("Could not write to file.", e);
		}
	}

	/**
	 * Creates a new file at the targeted file location and gives full file
	 * permissions to the current user.
	 * 
	 * @param newFile the file location
	 */
	public static void createFileAndGivePermissions(File newFile) {
		logger.debug("Creating new file at {}.", newFile.getAbsolutePath());

		newFile = new File(newFile.getAbsolutePath());

		if (!newFile.exists()) {
			try {
				newFile.createNewFile();
			} catch (IOException e) {
				throw new HabushuException("Could not create new file.", e);
			}
		}

		giveFullFilePermissions(newFile.getAbsolutePath());
	}

	/**
	 * Gives full read, write, and execute permissions to a file.
	 * 
	 * @param filePath the path to the file
	 */
	public static void giveFullFilePermissions(String filePath) {
		File file = new File(filePath);

		if (file.exists()) {
			file.setExecutable(true, false);
			file.setReadable(true, false);
			file.setWritable(true, false);
		}
	}
	
	/**
	 * Provides a way to configure the settings for a Maven user.
	 * @param newSettings the settings for the Maven user
	 */
	public static void setMavenSettings(Settings newSettings) {
		logger.debug("Changing settings contents.");
		settings = newSettings;
	}
}
