package de.northtommy.hibiscus.syncNorthTommy.advanzia;

import java.rmi.RemoteException;
import java.util.ArrayList;

import javax.annotation.Resource;

import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJobProviderKontoauszug;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.jobs.SynchronizeJob;

public class AdvanziaSynchronizeJobProviderKontoauszug extends SyncNTSynchronizeJobProviderKontoauszug
{
	@Resource
	private AdvanziaSynchronizeBackend backend = null;

	@Override
	protected AbstractSynchronizeBackend<?> getBackend() { return backend; }

	public AdvanziaSynchronizeJobProviderKontoauszug()
	{
		JOBS = new ArrayList<Class<? extends SynchronizeJob>>()
		{{
			add(AdvanziaSynchronizeJobKontoauszug.class);
		}};
	}

	@Override
	public boolean supports(Class type, Konto k)
	{
		try
		{
			// Advanzia Bank S.A., Luxemburg: IBAN LUkk 344x xxxx ...
			String iban = k.getIban();
			return iban != null && iban.replace(" ", "").toUpperCase().matches("LU\\d{2}344.*");
		}
		catch (RemoteException e)
		{
			return false;
		}
	}
}
