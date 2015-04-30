package org.openbakery.simulators

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * Created by rene on 30.04.15.
 */
class SimulatorsCreateTask extends DefaultTask {
	SimulatorControl simulatorControl


	public SimulatorsCreateTask() {
		setDescription("Delete and creates all iOS Simulators");
		simulatorControl = new SimulatorControl(project)
	}

	@TaskAction
	void run() {
		simulatorControl.deleteAll()
		simulatorControl.createAll()
	}
}
