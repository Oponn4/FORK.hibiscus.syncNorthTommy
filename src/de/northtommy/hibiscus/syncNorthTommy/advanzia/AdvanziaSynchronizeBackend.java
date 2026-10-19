package de.northtommy.hibiscus.syncNorthTommy.advanzia;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;

import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeBackend;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;
import de.willuhn.jameica.hbci.rmi.Konto;

@Lifecycle(Type.CONTEXT)
public class AdvanziaSynchronizeBackend extends SyncNTSynchronizeBackend
{
	public final static String META_NOTHEADLESS = "Browser anzeigen";
	public final static String META_SMS = "Zwei-Faktor per SMS";

	@Override
	public String getName()
	{
		return "Advanzia (gebuhrenfrei.com)";
	}

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
			result.add(META_NOTHEADLESS + "(true/false)");
			result.add(META_SMS + "(true/false)");
			return result;
		}
		catch (RemoteException e)
		{
			return null;
		}
	}
}
