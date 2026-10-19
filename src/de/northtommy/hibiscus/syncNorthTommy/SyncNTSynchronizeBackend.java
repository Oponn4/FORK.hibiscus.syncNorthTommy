package de.northtommy.hibiscus.syncNorthTommy;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Resource;

import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJobProvider;
import de.northtommy.hibiscus.syncNorthTommy.SyncNTSynchronizeJob;
import de.willuhn.annotation.Lifecycle;
import de.willuhn.annotation.Lifecycle.Type;
import de.willuhn.jameica.hbci.rmi.Konto;
import de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.SynchronizeBackend;
import de.willuhn.jameica.hbci.synchronize.SynchronizeEngine;
import de.willuhn.jameica.hbci.synchronize.SynchronizeJobProvider;
import de.willuhn.jameica.hbci.synchronize.SynchronizeSession;
import de.willuhn.jameica.system.Application;
import de.willuhn.jameica.system.OperationCanceledException;
import de.willuhn.logging.Logger;
import de.willuhn.util.ApplicationException;
import de.willuhn.util.ProgressMonitor;

public abstract class SyncNTSynchronizeBackend extends AbstractSynchronizeBackend 
{
	@Resource
	private SynchronizeEngine engine = null;

	@Override
	protected JobGroup createJobGroup(Konto k) {
		return new SyncNTJobGroup(k);
	}


	@Override
	protected Class<? extends SynchronizeJobProvider> getJobProviderInterface() {
		return SyncNTSynchronizeJobProvider.class;
	}

	/**
	 * @see de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend#getPropertyNames(de.willuhn.jameica.hbci.rmi.Konto)
	 */
	@Override
	public List<String> getPropertyNames(Konto konto)
	{
    	return null;
	}

	@Override
	public List<Konto> getSynchronizeKonten(Konto k)
	{
		List<Konto> list = super.getSynchronizeKonten(k);
		List<Konto> result = new ArrayList<Konto>();

		// Wir wollen nur die Offline-Konten und jene, bei denen Scripting explizit konfiguriert ist
		for (Konto konto:list)
		{
			if (konto != null)
			{
				SynchronizeBackend backend = engine.getBackend(konto);
				if (backend != null && backend.equals(this))
				{
					result.add(konto);
				}
			}
		}

		return result;
	}

	public boolean isSyncInterrupted()
	{
		return worker != null && worker.isInterrupted();
	}

	private final static Object NO_TASK_FIELD = new Object();
	private final static long IDLE_WAIT_MILLIS = 120 * 1000L;

	/**
	 * Jameica fuehrt immer nur eine Hintergrund-Aufgabe aus. Laeuft beim Start noch eine (das vorige Backend raeumt
	 * gerade auf, oder eine zweite Synchronisierung laeuft parallel), lehnt GUI.start den Worker nur mit der Logzeile
	 * "there's already running a background task" ab. AbstractSynchronizeBackend hat die Session da schon gesetzt und
	 * gibt sie nie frei: "Synchronisierung via ... laeuft bereits" bis zum Neustart.
	 * Deshalb erst warten, bis Jameica frei ist, und nach dem Start pruefen, ob der Worker angenommen wurde.
	 */
	@Override
	@SuppressWarnings({"rawtypes", "unchecked"})
	public synchronized SynchronizeSession execute(List jobs) throws ApplicationException, OperationCanceledException
	{
		waitForIdleController();
		SynchronizeSession session = super.execute(jobs);
		Object running = runningTask();
		if (running != null && running != NO_TASK_FIELD && running != worker)
		{
			Logger.error(getName() + ": Start abgelehnt, es laeuft bereits " + running.getClass().getName() + ", Session wird freigegeben");
			resetSession();
			throw new ApplicationException("Synchronisierung via " + getName() + " nicht gestartet: Es läuft bereits eine andere Hintergrund-Aufgabe.");
		}
		return session;
	}

	/**
	 * Die laufende Hintergrund-Aufgabe des Controllers (GUI.task, privat, daher per Reflection).
	 * NO_TASK_FIELD, wenn der Controller kein solches Feld hat.
	 */
	private static Object runningTask()
	{
		Object controller = Application.getController();
		if (controller == null)
			return NO_TASK_FIELD;
		for (Class<?> c = controller.getClass(); c != null; c = c.getSuperclass())
		{
			try
			{
				java.lang.reflect.Field f = c.getDeclaredField("task");
				f.setAccessible(true);
				return f.get(controller);
			}
			catch (NoSuchFieldException e)
			{
				// in der Oberklasse weitersuchen
			}
			catch (Exception e)
			{
				Logger.error("laufende Hintergrund-Aufgabe nicht lesbar", e);
				return NO_TASK_FIELD;
			}
		}
		return NO_TASK_FIELD;
	}

	/**
	 * Wartet, bis keine Hintergrund-Aufgabe mehr laeuft. Im GUI-Thread ("main") wird nicht gewartet, das wuerde die
	 * Oberflaeche einfrieren; dort entscheidet die Pruefung nach dem Start.
	 */
	private void waitForIdleController()
	{
		if ("main".equals(Thread.currentThread().getName()))
			return;
		long until = System.currentTimeMillis() + IDLE_WAIT_MILLIS;
		boolean logged = false;
		while (System.currentTimeMillis() < until)
		{
			Object running = runningTask();
			if (running == null || running == NO_TASK_FIELD)
				return;
			if (!logged)
			{
				Logger.info(getName() + ": warte auf laufende Hintergrund-Aufgabe " + running.getClass().getName());
				logged = true;
			}
			try
			{
				Thread.sleep(250L);
			}
			catch (InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return;
			}
		}
		Logger.warn(getName() + ": Hintergrund-Aufgabe nach " + (IDLE_WAIT_MILLIS / 1000) + " s nicht beendet, starte trotzdem");
	}

	/**
	 * Gibt die Session frei, die AbstractSynchronizeBackend.execute gesetzt hat (privates Feld).
	 */
	private void resetSession()
	{
		worker = null;
		try
		{
			java.lang.reflect.Field f = de.willuhn.jameica.hbci.synchronize.AbstractSynchronizeBackend.class.getDeclaredField("session");
			f.setAccessible(true);
			f.set(this, null);
		}
		catch (Exception e)
		{
			Logger.error("Session nicht freigegeben", e);
		}
	}

	protected class SyncNTJobGroup extends JobGroup
	{
		protected SyncNTJobGroup(Konto k) {
			super(k);
		}

		@Override
		protected void sync() throws Exception
		{
			////////////////////////////////////////////////////////////////////
			// lokale Variablen
			ProgressMonitor monitor = worker.getMonitor();

			////////////////////////////////////////////////////////////////////

			this.checkInterrupted();

			for (Object job:this.jobs)
			{
				this.checkInterrupted();

				SyncNTSynchronizeJob j = (SyncNTSynchronizeJob) job;
				j.execute(monitor);
			}
		}
	}
}
