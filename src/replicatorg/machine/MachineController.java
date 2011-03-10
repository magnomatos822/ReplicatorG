/*
 Part of the ReplicatorG project - http://www.replicat.org
 Copyright (c) 2008 Zach Smith

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software Foundation,
 Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package replicatorg.machine;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import javax.swing.JOptionPane;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import replicatorg.app.Base;
import replicatorg.app.GCodeParser;
import replicatorg.app.exceptions.BuildFailureException;
import replicatorg.app.tools.XML;
import replicatorg.app.ui.MainWindow;
import replicatorg.drivers.Driver;
import replicatorg.drivers.DriverFactory;
import replicatorg.drivers.DriverQueryInterface;
import replicatorg.drivers.EstimationDriver;
import replicatorg.drivers.OnboardParameters;
import replicatorg.drivers.RetryException;
import replicatorg.drivers.SDCardCapture;
import replicatorg.drivers.SimulationDriver;
import replicatorg.drivers.StopException;
import replicatorg.drivers.UsesSerial;
import replicatorg.drivers.commands.DriverCommand;
import replicatorg.machine.model.MachineModel;
import replicatorg.machine.model.ToolModel;
import replicatorg.model.GCodeSource;
import replicatorg.model.StringListSource;

/**
 * The MachineController object controls a single machine. It contains a single
 * machine driver object. All machine operations (building, stopping, pausing)
 * are performed asynchronously by a thread maintained by the MachineController;
 * calls to MachineController ordinarily trigger an operation and return
 * immediately.
 * 
 * When the machine is paused, the machine thread waits on notification to the
 * machine thread object.
 * 
 * In general, the machine thread should *not* be interrupted, as this can cause
 * synchronization issues. Interruption should only really happen on hanging
 * connections and shutdown.
 * 
 * @author phooky
 * 
 */
public class MachineController implements MachineControllerInterface {

	public enum RequestType {
		// Set up the connection to the machine
		CONNECT, // Establish connection with the driver
		DISCONNECT, // Detach from driver
		RESET, // ??

		// Start a build
		SIMULATE, // Build to the simulator
		BUILD_DIRECT, // Build in real time on the machine
		BUILD_TO_FILE, // Build, but instruct the machine to save it to the
						// local filesystem
		BUILD_TO_REMOTE_FILE, // Build, but instruct the machine to save it to
								// the machine's filesystem
		BUILD_REMOTE, // Instruct the machine to run a build from it's
						// filesystem

		// Control a build
		PAUSE, // Pause the current build
		UNPAUSE, // Unpause the current build
		STOP, // Abort the current build
		DISCONNECT_REMOTE_BUILD, // Disconnect from a remote build without
									// stopping it.

		// Interactive command
		RUN_COMMAND, // Run a single command on the driver, interleaved with the
						// build.
	}

	// Test idea for a request interface between the thread and the controller
	class MachineCommand {

		RequestType type;
		GCodeSource source;
		String remoteName;
		DriverCommand command;

		public MachineCommand(RequestType type, GCodeSource source,
				String remoteName) {
			this.type = type;
			this.source = source;
			this.remoteName = remoteName;
		}

		public MachineCommand(RequestType type, DriverCommand command) {
			this.type = type;
			this.command = command;
		}
	}

	public enum JobTarget {
		/** No target selected. */
		NONE,
		/** Operations are performed on a physical machine. */
		MACHINE,
		/** Operations are being simulated. */
		SIMULATOR,
		/** Operations are being captured to an SD card on the machine. */
		REMOTE_FILE,
		/** Operations are being captured to a file. */
		FILE
	};

	// Test idea for a print job: specifies a gcode source and a target
	class JobInformation {
		JobTarget target;
		GCodeSource source;

		public JobInformation(JobTarget target, GCodeSource source) {

		}
	}

	/**
	 * Get the machine state. This is a snapshot of the state when the method
	 * was called, not a live object.
	 * 
	 * @return a copy of the machine's state object
	 */
	public MachineState getMachineState() {
		return machineThread.getMachineState();
	}

	MachineThread machineThread;
	MachineCallbackHandler machineCallbackHandler;

	// TODO: WTF is this here for.
	// this is the xml config for this machine.
	protected Node machineNode;

	// The GCode source of the current build source.
	// TODO: We shouldn't keep something like this around here.
	protected GCodeSource source;

	public String getMachineName() {
		return machineThread.getMachineName();
	}

	// our current thread.
	protected Thread thread;

	/**
	 * Creates the machine object.
	 */
	public MachineController(Node mNode) {
		machineNode = mNode;
		machineThread = new MachineThread(this, mNode);
		machineThread.start();
		
		machineCallbackHandler = new MachineCallbackHandler();
		machineCallbackHandler.start();
	}

	public void setCodeSource(GCodeSource source) {
		this.source = source;
	}

	public boolean buildRemote(String remoteName) {
		machineThread.scheduleRequest(new MachineCommand(
				RequestType.BUILD_REMOTE, null, remoteName));
		return true;
	}

	/**
	 * Begin running a job.
	 */
	public boolean execute() {
		// start simulator

		// TODO: Re-enable the simulator.
		// if (simulator != null &&
		// Base.preferences.getBoolean("build.showSimulator",false))
		// simulator.createWindow();

		// estimate build time.
		Base.logger.info("Estimating build time...");
		estimate();

		// do that build!
		Base.logger.info("Beginning build.");

		machineThread.scheduleRequest(new MachineCommand(
				RequestType.BUILD_DIRECT, source, null));
		return true;
	}

	public boolean simulate() {
		// start simulator
		// if (simulator != null)
		// simulator.createWindow();

		// estimate build time.
		Base.logger.info("Estimating build time...");
		estimate();

		// do that build!
		Base.logger.info("Beginning simulation.");
		machineThread.scheduleRequest(new MachineCommand(RequestType.SIMULATE,
				source, null));
		return true;
	}

	public void estimate() {
		if (source == null) {
			return;
		}

		EstimationDriver estimator = new EstimationDriver();
		// TODO: Is this correct?
		estimator.setMachine(machineThread.getModel());

		Queue<DriverCommand> estimatorQueue = new LinkedList<DriverCommand>();

		GCodeParser estimatorParser = new GCodeParser();
		estimatorParser.init(estimator);

		// run each line through the estimator
		for (String line : source) {
			// TODO: Hooks for plugins to add estimated time?
			estimatorParser.parse(line, estimatorQueue);

			for (DriverCommand command : estimatorQueue) {
				try {
					command.run(estimator);
				} catch (RetryException r) {
					// Ignore.
				} catch (StopException e) {
					// TODO: Should we stop the estimator when we get a stop???
				}
			}
			estimatorQueue.clear();
		}

		// TODO: Set simulator up properly.
		// if (simulator != null) {
		// simulator.setSimulationBounds(estimator.getBounds());
		// }
		// // oh, how this needs to be cleaned up...
		// if (driver instanceof SimulationDriver) {
		// ((SimulationDriver)driver).setSimulationBounds(estimator.getBounds());
		// }

		machineThread.setEstimatedBuildTime(estimator.getBuildTime());
		Base.logger
				.info("Estimated build time is: "
						+ EstimationDriver.getBuildTimeString(estimator
								.getBuildTime()));
	}

	public DriverQueryInterface getDriverQueryInterface() {
		return (DriverQueryInterface) machineThread.getDriver();
	}

	public Driver getDriver() {
		Base.logger.severe("The driver should not be referenced directly!");
		return machineThread.getDriver();
	}

	public SimulationDriver getSimulatorDriver() {
		return machineThread.getSimulator();
	}

	public MachineModel getModel() {
		return machineThread.getModel();
	}

	public void stop() {
		machineThread.scheduleRequest(new MachineCommand(RequestType.STOP,
				null, null));
	}

	synchronized public boolean isInitialized() {
		return machineThread.isInitialized();
	}

	public void pause() {
		machineThread.scheduleRequest(new MachineCommand(RequestType.PAUSE,
				null, null));
	}

	public void upload(String remoteName) {
		/**
		 * Upload the gcode to the given remote SD name.
		 * 
		 * @param source
		 * @param remoteName
		 */
		machineThread.scheduleRequest(new MachineCommand(
				RequestType.BUILD_TO_REMOTE_FILE, source, remoteName));
	}

	public void buildToFile(String path) {
		// TODO: what happened to this?
	}

	public void unpause() {
		machineThread.scheduleRequest(new MachineCommand(RequestType.UNPAUSE,
				null, null));
	}

	public void reset() {
		machineThread.scheduleRequest(new MachineCommand(RequestType.RESET,
				null, null));
	}

	public void connect() {
		// recreate thread if stopped
		// TODO: Evaluate this!
		if (!machineThread.isAlive()) {
			machineThread = new MachineThread(this, machineNode);
			machineThread.start();
		}
		machineThread.scheduleRequest(new MachineCommand(RequestType.CONNECT,
				null, null));
	}

	synchronized public void disconnect() {
		machineThread.scheduleRequest(new MachineCommand(
				RequestType.DISCONNECT, null, null));
	}

	synchronized public boolean isPaused() {
		return getMachineState().isPaused();
	}

	public void runCommand(DriverCommand command) {
		machineThread.scheduleRequest(new MachineCommand(
				RequestType.RUN_COMMAND, command));
	}

	public void dispose() {
		if (machineThread != null) {
			machineThread.scheduleRequest(new MachineCommand(
					RequestType.DISCONNECT_REMOTE_BUILD, null, null));

			// Wait 5 seconds for the thread to stop.
			try {
				machineThread.join(5000);
			} catch (Exception e) {
				e.printStackTrace();
			}

			// TODO: Is this correct?
			machineThread.dispose();
		}
	}

	
	// Pass these on to our handler
	public void addMachineStateListener(MachineListener listener) {
		machineCallbackHandler.addMachineStateListener(listener);
	}

	public void removeMachineStateListener(MachineListener listener) {
		machineCallbackHandler.removeMachineStateListener(listener);
	}

	protected void emitStateChange(MachineState prev, MachineState current) {
		MachineStateChangeEvent e = new MachineStateChangeEvent(this, current,
				prev);
		
		machineCallbackHandler.schedule(e);
	}

	protected void emitProgress(MachineProgressEvent progress) {
		machineCallbackHandler.schedule(progress);
	}

	protected void emitToolStatus(ToolModel tool) {
		MachineToolStatusEvent e = new MachineToolStatusEvent(this, tool);
		machineCallbackHandler.schedule(e);
	}

	
	public int getLinesProcessed() {
		/*
		 * This is for jumping to the right line when aborting or pausing. This
		 * way you'll have the ability to track down where to continue printing.
		 */
		return machineThread.getLinesProcessed();
	}

	// TODO: Drop this
	public boolean isSimulating() {
		return machineThread.isSimulating();
	}

	// TODO: Drop this
	public boolean isInteractiveTarget() {
		return machineThread.isInteractiveTarget();
	}

	// TODO: Drop this
	public JobTarget getTarget() {
		return machineThread.currentTarget;
	}
}
