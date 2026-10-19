package de.northtommy.hibiscus.syncNorthTommy.traderepublic;

/**
 * Eine TR-Anmeldung je Abruf. Verrechnungskonto und Depot sind in Hibiscus zwei Konten mit je einem Job; ohne
 * diesen Speicher meldet sich jeder Job neu an und fragt ein eigenes OTP aus der App ab.
 * Der erste Job legt die Sitzungs-Cookies hier ab (nur im Speicher), die folgenden Jobs desselben Abrufs verwenden
 * sie weiter. Abgemeldet und verworfen wird nach dem letzten Job oder nach einem Fehler, spaetestens nach 10 Minuten.
 */
final class TraderepublicSessionCache
{
	private static final long MAX_AGE_MILLIS = 10 * 60 * 1000L;

	private static String user = null;
	private static String[] cookies = null;
	private static long createdAt = 0L;
	private static int pendingJobs = 0;

	private TraderepublicSessionCache()
	{
	}

	/**
	 * Neuer Abruf mit der angegebenen Zahl an TR-Jobs. Verwirft eine alte Sitzung.
	 */
	static synchronized void startRun(int jobs)
	{
		clear();
		pendingJobs = jobs;
	}

	/**
	 * Gemerkte Cookies fuer diesen Benutzer oder null, wenn keine oder zu alt.
	 * Reihenfolge: JSESSIONID, tr_device, tr_session, tr_claims, tr_refresh, tr_external_id.
	 */
	static synchronized String[] get(String forUser)
	{
		if (cookies == null || forUser == null || !forUser.equals(user))
			return null;
		if (System.currentTimeMillis() - createdAt > MAX_AGE_MILLIS)
		{
			clear();
			return null;
		}
		return cookies.clone();
	}

	static synchronized void put(String forUser, String[] sessionCookies)
	{
		user = forUser;
		cookies = sessionCookies.clone();
		createdAt = System.currentTimeMillis();
	}

	static synchronized void clear()
	{
		user = null;
		cookies = null;
		createdAt = 0L;
	}

	/**
	 * Ein Job ist fertig. true, wenn danach abgemeldet werden soll: kein weiterer TR-Job in diesem Abruf,
	 * oder der Job ist gescheitert. Dann wird die gemerkte Sitzung verworfen.
	 */
	static synchronized boolean finishJob(boolean failed)
	{
		pendingJobs--;
		boolean last = failed || pendingJobs <= 0;
		if (last)
		{
			clear();
			pendingJobs = 0;
		}
		return last;
	}
}
