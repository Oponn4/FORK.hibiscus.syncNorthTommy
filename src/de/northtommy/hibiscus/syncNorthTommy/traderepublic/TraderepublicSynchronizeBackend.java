package de.northtommy.hibiscus.syncNorthTommy.traderepublic;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;

import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeBackend;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.rmi.KontoType;
import de.willuhn.jameica.hbci.synchronize.SynchronizeSession;
import de.willuhn.jameica.system.OperationCanceledException;
import de.willuhn.util.ApplicationException;

@Lifecycle(Type.CONTEXT)
public class TraderepublicSynchronizeBackend extends SyncNTSynchronizeBackend
{
	//public final static String META_CHROMEPATH = "Pfad zu chrome.exe (optional bei Problemen)";
	public final static String META_FIREFOXPATH = "Pfad zu firefox.exe (optional bei Problemen)";
	public final static String META_NOTHEADLESS = "Browser beim Ermitteln Akamai-Header anzeigen";
	public final static String META_HEADERS = "Headerdaten";
	public final static String META_JSONDATA = "Speichere Debug-Daten";
	public final static String META_FULLHISTORY = "Beim nächsten Abruf komplette Historie neu einlesen";

    /**
     * Merkt sich die Zahl der TR-Jobs dieses Abrufs, damit sich alle TR-Konten eine Anmeldung teilen
     * (siehe TraderepublicSessionCache).
     */
    @Override
    @SuppressWarnings("rawtypes")
    public synchronized SynchronizeSession execute(List jobs) throws ApplicationException, OperationCanceledException
    {
        if (getCurrentSession() == null)
            TraderepublicSessionCache.startRun(jobs == null ? 0 : jobs.size());
        return super.execute(jobs);
    }

    @Override
    public String getName()
    {
        return "Traderepublic";
    }
    
    /**
     * @see de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend#getPropertyNames(de.willuhn.jameica.hbci.rmi.Konto)
     */
    @Override
    public List<String> getPropertyNames(Konto konto)
    {
		try 
		{
			if (konto == null || !konto.getBackendClass().equals(this.getClass().getName()))
			{
				return null;
			}

			List<String> result = new ArrayList<String>();
			result.add(META_FIREFOXPATH);
			result.add(META_NOTHEADLESS + "(true/false)");
	        if (KontoType.WERTPAPIERDEPOT.getValue() == konto.getAccountType()) {
				result.add(META_JSONDATA + "(true/false)");
				result.add(META_FULLHISTORY + "(true/false)");
			}
			return result;
		} 
		catch (RemoteException e) 
		{
			return null;
		}
    }
}
