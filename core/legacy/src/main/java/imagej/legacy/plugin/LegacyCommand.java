/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2012 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * 
 * The views and conclusions contained in the software and documentation are
 * those of the authors and should not be interpreted as representing official
 * policies, either expressed or implied, of any organization.
 * #L%
 */

package imagej.legacy.plugin;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Roi;
import imagej.ImageJ;
import imagej.command.Command;
import imagej.data.Dataset;
import imagej.data.display.ImageDisplay;
import imagej.data.display.ImageDisplayService;
import imagej.legacy.LegacyImageMap;
import imagej.legacy.LegacyOutputTracker;
import imagej.legacy.LegacyService;
import imagej.legacy.translate.DefaultImageTranslator;
import imagej.legacy.translate.Harmonizer;
import imagej.legacy.translate.ImageTranslator;
import imagej.legacy.translate.LegacyUtils;
import imagej.legacy.translate.ResultsTableHarmonizer;
import imagej.log.LogService;
import imagej.module.ItemIO;
import imagej.plugin.Parameter;
import imagej.ui.DialogPrompt;
import imagej.ui.UIService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

/**
 * Executes an ImageJ v1.x command.
 * 
 * @author Curtis Rueden
 * @author Barry DeZonia
 */
public class LegacyCommand implements Command {

	@Parameter
	private String className;

	@Parameter
	private String arg;

	@Parameter(type = ItemIO.OUTPUT)
	private List<ImageDisplay> outputs;

	@Parameter
	private ImageJ context;

	@Parameter
	private LogService log;

	@Parameter
	private ImageDisplayService imageDisplayService;

	@Parameter
	private LegacyService legacyService;

	@Parameter
	private UIService uiService;

	// -- LegacyCommand methods --

	/** Gets the list of output {@link ImageDisplay}s. */
	public List<ImageDisplay> getOutputs() {
		return Collections.unmodifiableList(outputs);
	}

	// -- Runnable methods --

	@Override
	public void run() {
		final ImageDisplay activeDisplay =
			imageDisplayService.getActiveImageDisplay();

		if (!isLegacyCompatible(activeDisplay)) {
			final String err =
				"The active dataset is not compatible with ImageJ v1.x.";
			log.error(err);
			notifyUser(err);
			outputs = new ArrayList<ImageDisplay>();
			return;
		}

		final LegacyCommandThread thread = new LegacyCommandThread();

		// enforce the desired order of thread execution
		try {
			thread.start();
			thread.join();
		}
		catch (final Exception e) {
			// will have been handled earlier
		}
	}

	// -- helper methods --

	private boolean isLegacyCompatible(final ImageDisplay display) {
		if (display == null) return true;
		final Dataset ds = imageDisplayService.getActiveDataset(display);
		return LegacyUtils.dimensionsIJ1Compatible(ds);
	}

	private void notifyUser(final String message) {
		uiService.showDialog(message, "Error",
			DialogPrompt.MessageType.INFORMATION_MESSAGE,
			DialogPrompt.OptionType.DEFAULT_OPTION);
	}

	// -- helper class --

	private class LegacyCommandThread extends Thread {

		final private ThreadGroup group;
		final private LegacyImageMap map;
		final private Harmonizer harmonizer;
		

		// NB - BDZ
		// In order to keep threads from waiting on each other unnecessarily when
		// multiple legacy plugins are running simultaneously we run the plugin
		// in its own thread group. waitForPluginThreads() only waits for those
		// threads in its group.

		public LegacyCommandThread() {
			super(new ThreadGroup("plugin thread group"), "plugin thread");
			this.group = getThreadGroup();
			this.map = legacyService.getImageMap();
			final ImageTranslator imageTranslator =
				new DefaultImageTranslator(context);
			this.harmonizer = new Harmonizer(context, imageTranslator);
		}

		@Override
		public void run() {
			
			ResultsTableHarmonizer rtHarmonizer = new ResultsTableHarmonizer(context);

			rtHarmonizer.setIJ1ResultsTable();

			final Set<ImagePlus> outputSet = LegacyOutputTracker.getOutputImps();
			final Set<ImagePlus> closedSet = LegacyOutputTracker.getClosedImps();

			harmonizer.resetTypeTracking();

			updateImagePlusesFromDisplays();

			//reportStackIssues("Before IJ1 plugin run");
			
			// must happen after updateImagePlusesFromDisplays()
			outputSet.clear();
			closedSet.clear();

			// set ImageJ1's active image
			legacyService.syncActiveImage();
			
			// set ImageJ1's colors
			legacyService.syncColors();
			
			try {
				// execute the legacy plugin
				IJ.runPlugIn(className, arg);

				// we always sleep at least once to make sure plugin has time to hatch
				// it's first thread if its going to create any.
				try {
					Thread.sleep(50);
				}
				catch (final InterruptedException e) {/**/}

				// wait for any threads hatched by plugin to terminate
				waitForPluginThreads();

				// sync modern displays to match existing legacy images
				outputs = updateDisplaysFromImagePluses();

				// close any displays that IJ1 wants closed
				for (final ImagePlus imp : closedSet) {
					final ImageDisplay disp = map.lookupDisplay(imp);
					if (disp != null) {
						// only close displays that have not been changed
						if (!outputs.contains(disp)) disp.close();
					}
				}

				// reflect any changes to globals in IJ2 options/prefs
				legacyService.updateIJ2Settings();

				//reportStackIssues("After IJ1 plugin run");
			}
			catch (final Exception e) {
				final String msg = "ImageJ 1.x plugin threw exception";
				log.error(msg, e);
				notifyUser(msg);
				// make sure our ImagePluses are in sync with original Datasets
				updateImagePlusesFromDisplays();
				// return no outputs
				outputs = new ArrayList<ImageDisplay>();
			}
			finally {
				// clean up - basically avoid dangling refs to large objects
				harmonizer.resetTypeTracking();
				outputSet.clear();
				closedSet.clear();
			}
			
			rtHarmonizer.setIJ2ResultsTable();
		}

		private void waitForPluginThreads() {
//			log.debug("LegacyCommand: begin waitForPluginThreads()");
			while (true) {
				boolean allDead = true;
				final List<Thread> currentThreads = getCurrentThreads();
				for (final Thread thread : currentThreads) {
					if (thread == Thread.currentThread()) continue;
					// Ignore some threads that IJ1 hatches that never terminate
					if (whitelisted(thread)) continue;
					if (thread.isAlive()) {
						// System.out.println(thread.getName() + " thread is alive");
						// reportThreadInfo(thread, currentThreads, threadsToIgnore);
						allDead = false;
						break;
					}
				}
				if (allDead) break;
				try {
					Thread.sleep(200);
				}
				catch (final Exception e) {/**/}
			}
//			log.debug("LegacyCommand: end waitForPluginThreads()");
		}

		private List<Thread> getCurrentThreads() {
			Thread[] threads;
			int numThreads;
			int size = 10;
			do {
				threads = new Thread[size];
				numThreads = group.enumerate(threads);
				size *= 2;
			}
			while (numThreads > threads.length);
			final List<Thread> threadList = new LinkedList<Thread>();
			for (int i = 0; i < numThreads; i++)
				threadList.add(threads[i]);
			return threadList;
		}

		/**
		 * Identifies threads that IJ1 hatches that don't terminate in a timely way.
		 */
		private boolean whitelisted(final Thread thread) {

			final String threadName = thread.getName();

			// the wait loop generates a timer that needs to be ignored. Ignoring all
			// timers is likely fine because associated worker threads should exist
			// too.
			if (threadName.startsWith("Timer")) return true;

			// StackWindow slider selector thread: thread does not go away until the
			// window closes.
			if (threadName.equals("zSelector")) return true;

			// threads that load images from web can sleep a long time waiting after
			// their data has already been loaded
			if (threadName.contains("Image Fetcher") &&
				thread.getState() == Thread.State.TIMED_WAITING)
			{
				return true;
			}
			/*
				// select by class name
				System.out.println("---"+thread.getClass().getDeclaringClass());
				System.out.println("---"+thread.getClass().getEnclosingClass());
				System.out.println("---"+thread.getClass().getCanonicalName());
				System.out.println("---"+thread.getClass().getName());
				System.out.println("---"+thread.getClass().getSimpleName());
				System.out.println("---"+thread.getClass().getClass());
				System.out.println("---"+thread.getClass().getInterfaces());
				
				// select by name of runnable class it owns
				//System.out.println(thread.HOW???);
			*/

			return false;
		}

		// TODO - IJ2 could modify an image to go outside IJ1's legal bounds. If it
		// has a existing ImagePlus mapping then we are likely assuming its legal
		// when its not. Put in tests to address this situation rather than having
		// harmonization or something else fail.

		private void updateImagePlusesFromDisplays() {
			// TODO - track events and keep a dirty bit, then only harmonize those
			// displays that have changed. See ticket #546.
			final List<ImageDisplay> imageDisplays =
				imageDisplayService.getImageDisplays();
			for (final ImageDisplay display : imageDisplays) {
				ImagePlus imp = map.lookupImagePlus(display);
				if (imp == null) {
					if (!isLegacyCompatible(display)) {
						continue;
					}
					imp = map.registerDisplay(display);
				}
				else { // imp already exists : update it
					// NB - it is possible a runtime exception in an IJ1 plugin left the
					// ImagePlus in a locked state. Make sure its unlocked going forward.
					imp.unlock();
				}
				harmonizer.updateLegacyImage(display, imp);
				harmonizer.registerType(imp);
			}
		}

		private List<ImageDisplay> updateDisplaysFromImagePluses() {
			// TODO - check the changes flag for each ImagePlus that already has a
			// ImageDisplay and only harmonize those that have changed. Maybe changes
			// flag does not track everything (such as metadata changes?) and thus
			// we might still have to do some minor harmonization. Investigate.

			final Set<ImagePlus> imps = LegacyOutputTracker.getOutputImps();
			final ImagePlus currImp = WindowManager.getCurrentImage();

			// see method below
			finishInProgressPastes(currImp, imps);

			// the IJ1 plugin may not have any outputs but just changes current
			// ImagePlus make sure we catch any changes via harmonization
			final List<ImageDisplay> displays = new ArrayList<ImageDisplay>();
			if (currImp != null) {
				ImageDisplay display = map.lookupDisplay(currImp);
				if (display != null) {
					harmonizer.updateDisplay(display, currImp);
				}
				else {
					display = map.registerLegacyImage(currImp);
					displays.add(display);
				}
			}

			// also harmonize any outputs

			for (final ImagePlus imp : imps) {
				if (imp.getStack().getSize() == 0) { // totally emptied by plugin
					// TODO - do we need to delete display or is it already done?
				}
				else { // image plus is not totally empty
					ImageDisplay display = map.lookupDisplay(imp);
					if (display == null) {
						if (imp.getWindow() != null) {
							display = map.registerLegacyImage(imp);
						}
						else {
							continue;
						}
					}
					else {
						if (imp == currImp) {
							// we harmonized this earlier
						}
						else harmonizer.updateDisplay(display, imp);
					}
					displays.add(display);
				}
			}

			return displays;
		}

		// SAVE - useful
		/*
		private void reportThreadInfo(Thread thread, List<Thread> allThreads, List<Thread> threadsToIgnore) {
			System.out.println("Thread "+thread.getId()+" is alive");
			System.out.println("  priority "+thread.getPriority()+" of "+thread.getThreadGroup().getMaxPriority());
			System.out.println("  interrupted "+thread.isInterrupted());
			System.out.println("  Other threads");
			for (final Thread t : allThreads) {
				if (threadsToIgnore.contains(t)) continue;
				if (whitelisted(t)) continue;
				if (t.isAlive()) {
					System.out.println("    id = "+t.getId()+ " name = "+thread.getName());
					System.out.println("      priority = "+t.getPriority());
					System.out.println("      interrupted = "+t.isInterrupted());
					System.out.println("      state = "+t.getState());
					if (t == Thread.currentThread()) System.out.println("      its the current thread");
					// Ignore some threads that IJ1 hatches that never terminate
				}
			}
		}
		*/

		// Finishes any in progress paste() operations. Done before harmonization.
		// In IJ1 the paste operations are usually handled by ImageCanvas::paint().
		// In IJ2 that method is never called. It would be nice to hook something
		// that calls paint() via the legacy injector but that may raise additional
		// problems. This is a simple fix.

		private void finishInProgressPastes(final ImagePlus currImp,
			final Set<ImagePlus> outputList)
		{
			endPaste(currImp);
			for (final ImagePlus imp : outputList) { // potentially empty list
				if (imp == currImp) continue;
				endPaste(imp);
			}
		}

		private void endPaste(final ImagePlus imp) {
			if (imp == null) return;
			final Roi roi = imp.getRoi();
			if (roi == null) return;
			if (roi.getPasteMode() == Roi.NOT_PASTING) return;
			roi.endPaste();
		}

		/* save for debugging if null pixel array exception ever reported
		 * 
		private void reportStackIssues(String contextMsg) {
			System.out.println("Stack issue report: "+contextMsg);
			final List<ImageDisplay> imageDisplays =
					imageDisplayService.getImageDisplays();
			System.out.println("  num displays == "+imageDisplays.size());
			int numDisp = imageDisplays.size();
			for (int i = 0; i < numDisp; i++) {
				ImageDisplay disp1 = imageDisplays.get(i);
				for (int j = i+1; j < numDisp; j++) {
					ImageDisplay disp2 = imageDisplays.get(j);
					ImagePlus imp1 = map.lookupImagePlus(disp1);
					ImagePlus imp2 = map.lookupImagePlus(disp2);
					if (imp1 == imp2) {
						System.out.println("  Two displays map to same ImagePlus!!!");
						System.out.println("    "+imp1.getTitle());
						System.out.println("    "+imp2.getTitle());
					}
					else if (imp1.getStack() == imp2.getStack()) {
						System.out.println("  Two displays share same ImageStack!!!");
						System.out.println("    "+imp1.getTitle());
						System.out.println("    "+imp2.getTitle());
					}
				}
			}
		}
		*/
	}

}
