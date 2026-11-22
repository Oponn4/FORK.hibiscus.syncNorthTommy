package de.northtommy.hibiscus.syncNorthTommy.traderepublic;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;

import de.willuhn.jameica.system.Application;

/**
 * Merkt sich je TR-Zugang die Geraete-Kennung der ersten erfolgreichen Anmeldung: den Header x-tr-device-info und
 * das Cookie tr_device. Ohne das kommt jeder Abruf als neues Geraet an (frischer Browser, andere stableDeviceId),
 * und Trade Republic schickt jedes Mal die Mail "Web-Login mit neuem Geraet" (gesehen 22.11.2026).
 * Datei im Jameica-Arbeitsverzeichnis, nur fuer den Besitzer lesbar; die Telefonnummer steht nur gehasht darin.
 */
final class TraderepublicDeviceStore
{
	private static final String FILE = "syncnt-traderepublic-device.properties";

	private TraderepublicDeviceStore()
	{
	}

	static synchronized String getDeviceInfo(String user)
	{
		return load().getProperty(key(user) + ".deviceInfo");
	}

	static synchronized String getTrDevice(String user)
	{
		return load().getProperty(key(user) + ".trDevice");
	}

	/** Speichert nur fehlende oder geaenderte Werte; null/leer wird ignoriert. */
	static synchronized void remember(String user, String deviceInfo, String trDevice) throws Exception
	{
		Properties p = load();
		boolean changed = false;
		changed |= put(p, key(user) + ".deviceInfo", deviceInfo);
		changed |= put(p, key(user) + ".trDevice", trDevice);
		if (changed)
			save(p);
	}

	private static boolean put(Properties p, String k, String v)
	{
		if (v == null || v.isBlank() || v.equals(p.getProperty(k)))
			return false;
		p.setProperty(k, v);
		return true;
	}

	private static String key(String user) {
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(user).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest).substring(0, 16);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static Path path()
	{
		return Path.of(Application.getConfig().getWorkDir(), FILE);
	}

	private static Properties load()
	{
		Properties p = new Properties();
		Path f = path();
		if (Files.isRegularFile(f)) {
			try (InputStream in = Files.newInputStream(f)) {
				p.load(in);
			} catch (Exception e) {
				// unreadable file: behave like the first login
			}
		}
		return p;
	}

	private static void save(Properties p) throws Exception
	{
		Path f = path();
		Path tmp = f.resolveSibling(FILE + ".tmp");
		try (OutputStream out = Files.newOutputStream(tmp)) {
			p.store(out, "SyncNT Trade Republic: Geraete-Kennung je Zugang");
		}
		try {
			Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException e) {
			// not a POSIX file system (Windows)
		}
		Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
