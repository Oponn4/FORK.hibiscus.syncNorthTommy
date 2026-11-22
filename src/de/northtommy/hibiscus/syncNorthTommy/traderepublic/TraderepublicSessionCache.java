package de.northtommy.hibiscus.syncNorthTommy.traderepublic;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Eine TR-Anmeldung fuer kurz hintereinander laufende Abrufe. Verrechnungskonto und Depot sind in Hibiscus zwei
 * Konten mit je einem Job; ohne diesen Speicher meldet sich jeder Job neu an und fragt eine eigene Bestaetigung in
 * der App ab. Das gilt auch, wenn die Konten in getrennten Laeufen kommen: der MCP-Abruf (hibiscus.ly.reports)
 * startet je Konto einen eigenen Lauf (gesehen 22.11.2026: zwei Bestaetigungen fuer einen Abruf).
 *
 * Der erste Job legt die Sitzungs-Cookies hier ab (nur im Speicher), folgende Jobs verwenden sie weiter, solange
 * die letzte Nutzung hoechstens IDLE_MILLIS her ist. Nach einem Fehler wird abgemeldet und verworfen. Ohne Fehler
 * wird nicht abgemeldet, damit der naechste Lauf die Sitzung noch nutzen kann; sie verfaellt danach hier und bei TR.
 */
final class TraderepublicSessionCache
{
	private static final long IDLE_MILLIS = 3 * 60 * 1000L;

	private static String user = null;
	private static Map<String, String> cookies = null;
	private static long lastUsed = 0L;

	private TraderepublicSessionCache()
	{
	}

	/**
	 * Gemerkte Sitzungs-Cookies (Name -> Wert) fuer diesen Benutzer oder null, wenn keine oder zu lange unbenutzt.
	 */
	static synchronized Map<String, String> get(String forUser)
	{
		if (cookies == null || forUser == null || !forUser.equals(user))
			return null;
		if (System.currentTimeMillis() - lastUsed > IDLE_MILLIS)
		{
			clear();
			return null;
		}
		lastUsed = System.currentTimeMillis();
		return new LinkedHashMap<>(cookies);
	}

	static synchronized void put(String forUser, Map<String, String> sessionCookies)
	{
		user = forUser;
		cookies = new LinkedHashMap<>(sessionCookies);
		lastUsed = System.currentTimeMillis();
	}

	static synchronized void clear()
	{
		user = null;
		cookies = null;
		lastUsed = 0L;
	}

	/**
	 * Ein Job ist fertig. true, wenn danach abgemeldet werden soll: nur nach einem Fehler, dann wird die gemerkte
	 * Sitzung verworfen. Ohne Fehler bleibt sie fuer den naechsten Job oder Lauf (siehe IDLE_MILLIS).
	 */
	static synchronized boolean finishJob(boolean failed)
	{
		if (failed)
		{
			clear();
			return true;
		}
		lastUsed = System.currentTimeMillis();
		return false;
	}
}
