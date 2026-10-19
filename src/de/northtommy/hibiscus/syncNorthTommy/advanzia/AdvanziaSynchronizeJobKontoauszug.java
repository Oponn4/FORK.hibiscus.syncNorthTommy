package de.northtommy.hibiscus.syncNorthTommy.advanzia;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Resource;

import org.json.JSONArray;
import org.json.JSONObject;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.RequestOptions;

import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJob;
import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJobKontoauszug;
import de.willuhn.datasource.rmi.DBIterator;
import de.willuhn.jameica.hbci.Settings;
import de.willuhn.jameica.hbci.messaging.ObjectChangedMessage;
import de.willuhn.jameica.hbci.messaging.ObjectDeletedMessage;
import de.willuhn.jameica.hbci.messaging.SaldoMessage;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.Umsatz;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.system.Application;
import de.willuhn.jameica.system.OperationCanceledException;
import de.willuhn.logging.Level;
import de.willuhn.util.ApplicationException;

/**
 * Umsatz- und Saldoabruf fuer Kreditkarten der Advanzia Bank (gebuhrenfrei.com).
 *
 * Login laeuft ueber einen Playwright-Browser, weil das Portal (OIDC ueber id.gebuhrenfrei.com)
 * einen Browser-Fingerprint mitschickt. Die zweite Stufe ist die Freigabe in der Advanzia-App
 * (alternativ per SMS-Code, Konto-Einstellung "Zwei-Faktor per SMS"). Danach werden die JSON-APIs des Portals mit den Cookies der
 * Browser-Sitzung abgefragt.
 */
public class AdvanziaSynchronizeJobKontoauszug extends SyncNTSynchronizeJobKontoauszug implements SyncNTSynchronizeJob
{
	private static final String PORTAL = "https://mein.gebuhrenfrei.com";
	private static final String PORTAL_START = PORTAL + "/b2c/de/";
	private static final String ID_HOST = "https://id.gebuhrenfrei.com";
	private static final int APP_CONFIRMATION_TIMEOUT_SECONDS = 180;
	private static final int MAX_MONTHS_WITHOUT_EXISTING_DATA = 36;

	@Resource
	private AdvanziaSynchronizeBackend backend = null;

	private boolean preferSms = false;

	@Override
	protected SynchronizeBackend getBackend() { return backend; }

	@Override
	public boolean process(Konto konto, boolean fetchSaldo, boolean fetchUmsatz, DBIterator<Umsatz> umsaetze, String user, String passwort) throws Exception
	{
		boolean headless = "false".equals(konto.getMeta(AdvanziaSynchronizeBackend.META_NOTHEADLESS, "false"));
		preferSms = "true".equals(konto.getMeta(AdvanziaSynchronizeBackend.META_SMS, "false"));

		try (Playwright playwright = Playwright.create())
		{
			var launchOptions = new BrowserType.LaunchOptions().setHeadless(headless);
			if (proxyConfig != null && proxyConfig.getProxyHost() != null)
			{
				launchOptions.setProxy(proxyConfig.getProxyScheme() + "://" + proxyConfig.getProxyHost() + ":" + proxyConfig.getProxyPort());
			}
			log(Level.INFO, "Starte Browser ...");
			Browser browser = playwright.firefox().launch(launchOptions);
			try
			{
				BrowserContext context = browser.newContext(new Browser.NewContextOptions().setLocale("de-DE"));
				Page page = context.newPage();
				monitor.setPercentComplete(5);

				login(page, context.request(), user, passwort);
				monitor.setPercentComplete(30);

				APIRequestContext api = context.request();
				String accountId = findAccountId(api, konto);

				JSONObject details = getJson(api, PORTAL + "/api/accounts/" + accountId + "/details");
				double balance = details.optDouble("balance", 0.0);
				if (fetchSaldo)
				{
					konto.setSaldo(balance);
					if (details.has("openToBuy"))
					{
						konto.setSaldoAvailable(details.optDouble("openToBuy"));
					}
					konto.store();
					Application.getMessagingFactory().sendMessage(new SaldoMessage(konto));
					log(Level.INFO, "Saldo aktualisiert: " + balance);
				}
				monitor.setPercentComplete(40);

				if (fetchUmsatz)
				{
					importTransactions(api, konto, accountId, balance, umsaetze);
				}
			}
			finally
			{
				try { browser.close(); } catch (Exception e) { }
			}
		}
		return true;
	}

	private void login(Page page, APIRequestContext api, String user, String passwort) throws Exception
	{
		log(Level.INFO, "Oeffne Login-Seite ...");
		page.navigate(PORTAL_START);
		page.waitForSelector("#username");
		page.fill("#username", user);
		page.fill("#password", passwort);

		log(Level.INFO, "Sende Zugangsdaten ...");
		Response credentialsResponse = page.waitForResponse(
				r -> r.url().contains("/api/userlogin/verify-credentials"),
				() -> page.press("#password", "Enter"));
		if (credentialsResponse.status() != 200)
		{
			throw new ApplicationException("Login fehlgeschlagen (HTTP " + credentialsResponse.status() + ")");
		}
		JSONObject credentials = new JSONObject(credentialsResponse.text());
		String validation = credentials.optString("validation");
		if (!"success".equalsIgnoreCase(validation))
		{
			throw new ApplicationException("failed".equalsIgnoreCase(validation)
					? "Login abgelehnt, Benutzername oder Passwort falsch?"
					: "Login abgelehnt: " + validation);
		}

		String nextStep = credentials.optString("nextStep");
		log(Level.DEBUG, "Naechster Login-Schritt: " + nextStep);
		String redirectUrl;
		if ("mfa".equals(nextStep))
		{
			redirectUrl = selectMfaAndConfirm(api, credentials);
		}
		else if ("appConfirmation".equals(nextStep))
		{
			redirectUrl = waitForAppConfirmation(api, "");
		}
		else if ("otp".equals(nextStep) || "smsOtp".equals(nextStep))
		{
			redirectUrl = verifyOtp(api);
		}
		else
		{
			throw new ApplicationException("Unbekannter Login-Schritt: " + nextStep);
		}

		if (redirectUrl.startsWith("/"))
		{
			redirectUrl = ID_HOST + redirectUrl;
		}
		log(Level.INFO, "Login bestaetigt, oeffne Portal ...");
		page.navigate(redirectUrl);
		page.waitForURL(url -> url.startsWith(PORTAL) && !url.contains("signin-oidc"));
		log(Level.INFO, "Login erfolgreich.");
	}

	private String selectMfaAndConfirm(APIRequestContext api, JSONObject credentials) throws Exception
	{
		JSONArray methods = credentials.optJSONArray("mfaMethods");
		JSONArray devices = credentials.optJSONArray("devices");
		boolean appAvailable = !preferSms && devices != null && devices.length() > 0
				&& (methods == null || methods.toString().toLowerCase().contains("app"));
		log(Level.DEBUG, "Angebotene Zwei-Faktor-Methoden: " + methods);

		JSONObject choice = new JSONObject();
		if (appAvailable)
		{
			choice.put("mfaChoice", "appConfirmation");
			choice.put("deviceId", devices.getJSONObject(0).optString("deviceId"));
		}
		else
		{
			choice.put("mfaChoice", "otp");
			choice.put("deviceId", "");
		}
		APIResponse r = api.post(ID_HOST + "/api/userlogin/select-mfa", jsonRequest(choice));
		if (!r.ok())
		{
			throw new ApplicationException("Auswahl der Zwei-Faktor-Methode fehlgeschlagen (HTTP " + r.status() + ")");
		}
		String code = new JSONObject(r.text()).optString("appConfirmationCode", "");
		return appAvailable ? waitForAppConfirmation(api, code) : verifyOtp(api);
	}

	private String waitForAppConfirmation(APIRequestContext api, String code) throws Exception
	{
		log(Level.INFO, "Bitte den Login in der Advanzia-App bestaetigen" + (code.isBlank() ? "" : ", Code in der App: " + code) + " ...");
		long end = System.currentTimeMillis() + APP_CONFIRMATION_TIMEOUT_SECONDS * 1000L;
		while (System.currentTimeMillis() < end)
		{
			checkCanceled();
			APIResponse r = api.get(ID_HOST + "/api/userlogin/appconfirmation-performed", baseRequest());
			if (r.status() == 200)
			{
				String redirect = new JSONObject(r.text()).optString("redirectUrl");
				if (!redirect.isBlank())
				{
					return redirect;
				}
			}
			else if (r.status() != 204)
			{
				throw new ApplicationException("Freigabe in der App fehlgeschlagen oder abgelehnt (HTTP " + r.status() + ")");
			}
			Thread.sleep(2000);
		}
		throw new ApplicationException("Keine Freigabe in der App innerhalb von " + APP_CONFIRMATION_TIMEOUT_SECONDS + " Sekunden");
	}

	private String verifyOtp(APIRequestContext api) throws Exception
	{
		log(Level.INFO, "Bitte den Code aus der SMS von Advanzia eingeben ...");
		String otp = Application.getCallback().askUser("Bitte den Code aus der SMS von Advanzia eingeben", "Code:");
		if (otp == null || otp.isBlank())
		{
			throw new ApplicationException("Kein SMS-Code eingegeben");
		}
		APIResponse r = api.post(ID_HOST + "/api/userlogin/verify-otp", jsonRequest(new JSONObject().put("otp", otp.trim())));
		if (!r.ok())
		{
			throw new ApplicationException("SMS-Code abgelehnt (HTTP " + r.status() + ")");
		}
		String redirect = new JSONObject(r.text()).optString("redirectUrl");
		if (redirect.isBlank())
		{
			throw new ApplicationException("SMS-Code akzeptiert, aber keine Weiterleitung erhalten");
		}
		return redirect;
	}

	private String findAccountId(APIRequestContext api, Konto konto) throws Exception
	{
		JSONObject profile = getJson(api, PORTAL + "/api/customers/profile/v2/?refreshCache=true");
		JSONArray accounts = profile.getJSONObject("customerProfile").getJSONArray("accounts");
		String iban = konto.getIban() == null ? "" : konto.getIban().replace(" ", "").toUpperCase();
		String first = null;
		for (int i = 0; i < accounts.length(); i++)
		{
			String id = accounts.getJSONObject(i).getString("accountId");
			if (first == null)
			{
				first = id;
			}
			JSONObject details = getJson(api, PORTAL + "/api/accounts/" + id + "/details");
			if (iban.equals(details.optString("iban").replace(" ", "").toUpperCase()))
			{
				return id;
			}
		}
		if (first == null)
		{
			throw new ApplicationException("Kein Kartenkonto im Advanzia-Portal gefunden");
		}
		log(Level.WARN, "Kein Kartenkonto mit IBAN " + iban + " gefunden, verwende das erste Konto");
		return first;
	}

	private void importTransactions(APIRequestContext api, Konto konto, String accountId, double balance, DBIterator<Umsatz> umsaetze) throws Exception
	{
		SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd");

		// Bestand in Hibiscus: IDs frueherer Abrufe und juengstes Buchungsdatum (z. B. nach CSV-Import ohne IDs)
		Map<String, Umsatz> existingById = new HashMap<>();
		Set<String> existingDayAmount = new HashSet<>();
		Date newestExisting = null;
		umsaetze.begin();
		while (umsaetze.hasNext())
		{
			Umsatz u = umsaetze.next();
			if (u.getTransactionId() != null && !u.getTransactionId().isBlank())
			{
				existingById.put(u.getTransactionId(), u);
			}
			existingDayAmount.add(day.format(u.getDatum()) + "|" + Math.round(u.getBetrag() * 100));
			if (newestExisting == null || u.getDatum().after(newestExisting))
			{
				newestExisting = u.getDatum();
			}
		}
		LocalDate until = newestExisting == null ? null : LocalDate.parse(day.format(newestExisting));
		log(Level.INFO, "Umsaetze werden abgerufen" + (until == null ? " (komplette Historie)" : " ab " + until) + " ...");

		// Aktuelle Monate, danach rueckwaerts Monat fuer Monat
		List<JSONObject> fetched = new ArrayList<>();
		JSONObject current = getJson(api, PORTAL + "/api/accounts/" + accountId + "/transactions");
		addAll(fetched, current);
		YearMonth oldest = YearMonth.now();
		JSONArray months = current.optJSONArray("months");
		if (months != null)
		{
			for (int i = 0; i < months.length(); i++)
			{
				JSONObject m = months.getJSONObject(i);
				YearMonth ym = YearMonth.of(m.getInt("year"), m.getInt("monthStartingAtZero") + 1);
				if (ym.isBefore(oldest))
				{
					oldest = ym;
				}
			}
		}
		int emptyInRow = 0;
		for (int i = 1; i <= MAX_MONTHS_WITHOUT_EXISTING_DATA; i++)
		{
			checkCanceled();
			YearMonth ym = oldest.minusMonths(i);
			if (until != null && ym.atEndOfMonth().isBefore(until))
			{
				break;
			}
			APIResponse r = api.get(PORTAL + "/api/accounts/" + accountId + "/transactions/" + ym.getYear() + "/" + (ym.getMonthValue() - 1), baseRequest());
			if (!r.ok())
			{
				log(Level.DEBUG, "Monat " + ym + " nicht abrufbar (HTTP " + r.status() + "), Ende der Historie");
				break;
			}
			int before = fetched.size();
			addAll(fetched, new JSONObject(r.text()));
			emptyInRow = fetched.size() == before ? emptyInRow + 1 : 0;
			if (until == null && emptyInRow >= 3)
			{
				break;
			}
		}
		monitor.setPercentComplete(70);

		// neueste zuerst, fuer die Saldo-Rueckrechnung
		fetched.sort((a, b) -> b.optString("transactionDate").compareTo(a.optString("transactionDate")));

		List<Umsatz> neue = new ArrayList<>();
		Set<String> seenIds = new HashSet<>();
		LocalDate oldestFetched = null;
		double saldo = Math.round(balance * 100) / 100.0;
		for (JSONObject t : fetched)
		{
			String id = t.optString("reference");
			if (id.isBlank() || !seenIds.add(id))
			{
				continue;
			}
			boolean booked = "APPROVED".equalsIgnoreCase(t.optString("status"));
			double amount = t.optDouble("amount", 0.0);
			LocalDate date = LocalDate.parse(t.optString("transactionDate").substring(0, 10));
			if (oldestFetched == null || date.isBefore(oldestFetched))
			{
				oldestFetched = date;
			}
			double saldoAfter = saldo;
			if (booked)
			{
				saldo = Math.round((saldo - amount) * 100) / 100.0;
			}

			Umsatz existing = existingById.get(id);
			if (existing != null)
			{
				if (existing.hasFlag(Umsatz.FLAG_NOTBOOKED) && booked)
				{
					existing.setFlags(existing.getFlags() & ~Umsatz.FLAG_NOTBOOKED);
					existing.setSaldo(saldoAfter);
					existing.store();
					Application.getMessagingFactory().sendMessage(new ObjectChangedMessage(existing));
				}
				continue;
			}
			if (until != null && date.isBefore(until))
			{
				continue;
			}
			if (until != null && date.isEqual(until) && existingDayAmount.contains(date + "|" + Math.round(amount * 100)))
			{
				continue;
			}

			Umsatz u = (Umsatz) Settings.getDBService().createObject(Umsatz.class, null);
			u.setKonto(konto);
			u.setTransactionId(id);
			u.setBetrag(amount);
			Date d = day.parse(date.toString());
			u.setDatum(d);
			u.setValuta(d);
			u.setGegenkontoName(limit(t.optString("merchantName"), 70));
			u.setZweck(limit(t.optString("text"), 255));
			u.setArt(art(t.optString("classification")));
			JSONObject fx = t.optJSONObject("foreignAmount");
			if (fx != null)
			{
				u.setKommentar(String.format(Locale.GERMANY, "Fremdw\u00e4hrung: %.2f %s, Kurs %.4f",
						fx.optDouble("amount"), fx.optString("currency"), fx.optDouble("conversionRate")));
			}
			if (booked)
			{
				u.setSaldo(saldoAfter);
			}
			else
			{
				u.setFlags(Umsatz.FLAG_NOTBOOKED);
			}
			neue.add(u);
		}
		monitor.setPercentComplete(85);

		log(Level.INFO, neue.size() + " neue Umsaetze, importiere ...");
		// neue liegt neueste zuerst vor, reverseImport speichert von hinten (aelteste zuerst)
		reverseImport(neue);

		// Vorgemerkte Umsaetze, die das Portal nicht mehr liefert, sind storniert
		if (oldestFetched != null)
		{
			for (Umsatz u : existingById.values())
			{
				if (u.hasFlag(Umsatz.FLAG_NOTBOOKED) && !seenIds.contains(u.getTransactionId())
						&& !LocalDate.parse(day.format(u.getDatum())).isBefore(oldestFetched))
				{
					var dbId = u.getID();
					u.delete();
					Application.getMessagingFactory().sendMessage(new ObjectDeletedMessage(u, dbId));
				}
			}
		}
		monitor.setPercentComplete(95);
	}

	private void checkCanceled() throws OperationCanceledException
	{
		if (backend != null && backend.isSyncInterrupted())
		{
			throw new OperationCanceledException("Synchronisierung abgebrochen");
		}
	}

	private static void addAll(List<JSONObject> target, JSONObject page)
	{
		JSONArray list = page.optJSONArray("transactions");
		if (list == null)
		{
			return;
		}
		for (int i = 0; i < list.length(); i++)
		{
			target.add(list.getJSONObject(i));
		}
	}

	private static String art(String classification)
	{
		switch (classification == null ? "" : classification.toUpperCase())
		{
			case "PURCHASE": return "Kartenumsatz";
			case "PAYMENT": return "Zahlung";
			case "FEE": return "Gebuehr";
			case "INTEREST": return "Zinsen";
			case "REFUND": return "Gutschrift";
			default: return classification;
		}
	}

	private static String limit(String s, int max)
	{
		if (s == null)
		{
			return "";
		}
		return s.length() > max ? s.substring(0, max) : s;
	}

	private JSONObject getJson(APIRequestContext api, String url) throws Exception
	{
		APIResponse r = api.get(url, baseRequest());
		if (!r.ok())
		{
			throw new ApplicationException("Abruf fehlgeschlagen: " + url.replace(PORTAL, "") + " (HTTP " + r.status() + ")");
		}
		return new JSONObject(r.text());
	}

	private static RequestOptions baseRequest()
	{
		return RequestOptions.create().setHeader("X-CorrelationId", UUID.randomUUID().toString());
	}

	private static RequestOptions jsonRequest(JSONObject body)
	{
		return baseRequest().setHeader("Content-Type", "application/json").setData(body.toString());
	}
}
