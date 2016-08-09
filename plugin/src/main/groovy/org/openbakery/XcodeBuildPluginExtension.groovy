/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openbakery

import org.apache.commons.io.filefilter.SuffixFileFilter
import org.apache.commons.lang.StringUtils
import org.gradle.api.Project
import org.gradle.util.ConfigureUtil
import org.openbakery.signing.Signing
import org.openbakery.simulators.SimulatorControl
import org.openbakery.simulators.SimulatorDevice
import org.openbakery.simulators.SimulatorRuntime
import org.openbakery.tools.Xcode
import org.openbakery.tools.Xcodebuild
import org.openbakery.tools.XcodebuildParameters
import org.openbakery.util.PlistHelper
import org.openbakery.util.VariableResolver
import org.slf4j.Logger
import org.slf4j.LoggerFactory

public enum Devices {
	UNIVERSAL,
	PHONE,
	PAD,
	WATCH
}

/*

^
should be migrated to this -> and renamed to Device

enum Devices {
	PHONE(1<<0),
	PAD(1<<1),
	WATCH(1<<2),
	TV(1<<3)

	private final int value;

	Devices(int value) {
		this.value = value
	}

	public int getValue() {
		return value
	}

	public boolean is(Devices device) {
		return (this.value & device.value) > 0
	}

}
 */

public enum Type {
	iOS("iOS"),
	OSX("OSX"),
	tvOS("tvOS"),
	watchOS("watchOS")


	String value;

	public Type(String value) {
		this.value = value;
	}

	public static Type typeFromString(String string) {
		if (string == null) {
			return iOS;
		}
		for (Type type in Type.values()) {
			if (string.toLowerCase().startsWith(type.value.toLowerCase())) {
				return type;
			}
		}
		return iOS;
	}
}


class XcodeBuildPluginExtension {
	public final static KEYCHAIN_NAME_BASE = "gradle-"


	private static Logger logger = LoggerFactory.getLogger(XcodeBuildPluginExtension.class)


	String infoPlist = null
	String scheme = null
	String configuration = 'Debug'
	boolean simulator = true
	Type type = Type.iOS

	String target = null
	Object dstRoot
	Object objRoot
	Object symRoot
	Object sharedPrecompsDir
	Object derivedDataPath
	String sourceDirectory = '.'
	Signing signing = null
	def additionalParameters = null
	String bundleNameSuffix = null
	List<String> arch = null
	String workspace = null
	String xcodeVersion = null
	Map<String, String> environment = null
	String productName = null
	String bundleName = null
	String productType = "app"
	String ipaFileName = null
	File projectFile

	Devices devices = Devices.UNIVERSAL;

	Set<Destination> destinations = null

	CommandRunner commandRunner
	VariableResolver variableResolver
	PlistHelper plistHelper


	SimulatorControl simulatorControl
	Xcode xcode

	HashMap<String, BuildTargetConfiguration> projectSettings = new HashMap<>()



	/**
	 * internal parameters
	 */
	private final Project project

	public XcodeBuildPluginExtension(Project project) {
		this.project = project;
		this.signing = new Signing(project)
		this.variableResolver = new VariableResolver(project)
		commandRunner = new CommandRunner()
		plistHelper = new PlistHelper(this.project, commandRunner)

		this.dstRoot = {
			return project.getFileResolver().withBaseDir(project.getBuildDir()).resolve("dst")
		}

		this.objRoot = {
			return project.getFileResolver().withBaseDir(project.getBuildDir()).resolve("obj")
		}

		this.symRoot = {
			return project.getFileResolver().withBaseDir(project.getBuildDir()).resolve("sym")
		}

		this.sharedPrecompsDir = {
			return project.getFileResolver().withBaseDir(project.getBuildDir()).resolve("shared")
		}

		this.derivedDataPath = {
			return project.getFileResolver().withBaseDir(project.getBuildDir()).resolve("derivedData")
		}

	}

	String getWorkspace() {
		if (workspace != null) {
			return workspace
		}
		String[] fileList = project.projectDir.list(new SuffixFileFilter(".xcworkspace"))
		if (fileList.length) {
			return fileList[0]
		}
		return null
	}

	void setDerivedDataPath(File derivedDataPath) {
		this.derivedDataPath = derivedDataPath
	}

	void setDstRoot(File dstRoot) {
		this.dstRoot = dstRoot
	}

	void setObjRoot(File objRoot) {
		this.objRoot = objRoot
	}

	void setSymRoot(File symRoot) {
		this.symRoot = symRoot
	}

	void setSharedPrecompsDir(File sharedPrecompsDir) {
		this.sharedPrecompsDir = sharedPrecompsDir
	}

	File getDstRoot() {
		if (dstRoot instanceof File) {
			return dstRoot
		}
		return project.file(dstRoot)
	}

	File getObjRoot() {
		if (objRoot instanceof File) {
			return objRoot
		}
		return project.file(objRoot)
	}

	File getSymRoot() {
		if (symRoot instanceof File) {
			return symRoot
		}
		return project.file(symRoot)
	}

	File getSharedPrecompsDir() {
		if (sharedPrecompsDir instanceof File) {
			return sharedPrecompsDir
		}
		return project.file(sharedPrecompsDir)
	}

	File getDerivedDataPath() {
		if (derivedDataPath instanceof File) {
			return derivedDataPath
		}
		return project.file(derivedDataPath)
	}

	void signing(Closure closure) {
		ConfigureUtil.configure(closure, this.signing)
	}


	boolean isSimulatorBuildOf(Type expectedType) {
		if (type != expectedType) {
			logger.debug("is no simualtor build")
			return false;
		}
		logger.debug("is simualtor build {}", this.simulator)
		return this.simulator;
	}

	boolean isDeviceBuildOf(Type expectedType) {
		if (type != expectedType) {
			return false;
		}
		return !this.simulator
	}

	void destination(Closure closure) {
		Destination destination = new Destination()
		ConfigureUtil.configure(closure, destination)
		if (destinations == null) {
			destinations = [] as Set
		}

		destinations << destination
	}

	void setDestination(def destination) {
		SimulatorRuntime runtime = getSimulatorControl().getMostRecentRuntime(Type.iOS)

		if (destination instanceof List) {
			destinations = [] as Set
			destination.each { singleDestination ->
				this.destination {
					name = singleDestination.toString()
					os = runtime.version.toString()
				}
			}

			return
		}

		this.destination {
			name = destination.toString()
			os = runtime.version.toString()
		}
	}

	List<Destination> getAllDestinations() {
		return getSimulatorControl().getAllDestinations(type)
	}


	void setArch(Object arch) {
		if (arch instanceof List) {
			logger.debug("Arch is List: " + arch + " - " + arch.getClass().getName())
			this.arch = arch;
		} else {
			logger.debug("Arch is string: " + arch + " - " + arch.getClass().getName())
			this.arch = new ArrayList<String>();
			this.arch.add(arch.toString());
		}
	}

	void setEnvironment(Object environment) {
		if (environment == null) {
			return
		}

		if (environment instanceof Map) {
			logger.debug("environment is Map: " + environment + " - " + environment.getClass().getName())
			this.environment = environment;
		} else {
			logger.debug("environment is string: " + environment + " - " + environment.getClass().getName())
			this.environment = new HashMap<String, String>();

			String environmentString = environment.toString()
			int index = environmentString.indexOf("=")
			if (index == -1) {
				this.environment.put(environmentString, null)
			} else {
				this.environment.put(environmentString.substring(0, index),environmentString.substring(index + 1))
			}
		}
	}


	void setVersion(String version) {
		this.xcodeVersion = version
		// check if the version is valid. On creation of the Xcodebuild class an exception is thrown if the version is not valid
		xcode = null
		getXcode()
	}


	String getValueFromInfoPlist(key) {
		try {
			logger.debug("project.projectDir {}", project.projectDir)
			File infoPlistFile = new File(project.projectDir, infoPlist)
			logger.debug("get value {} from plist file {}", key, infoPlistFile)
			return commandRunner.runWithResult([
							"/usr/libexec/PlistBuddy",
							infoPlistFile.absolutePath,
							"-c",
							"Print :" + key])
		} catch (IllegalStateException ex) {
			return null
		}
	}

	String getBundleName() {
		if (bundleName != null) {
			return bundleName
		}
		bundleName = getValueFromInfoPlist("CFBundleName")

		bundleName = variableResolver.resolve(bundleName);

		if (StringUtils.isEmpty(bundleName)) {
			bundleName = this.productName
		}
		return bundleName
	}

	File getOutputPath() {
		String path = getConfiguration()
		if (type == Type.iOS) {
			if (simulator) {
				path += "-iphonesimulator"
			} else {
				path += "-iphoneos"
			}
		}
		return new File(getSymRoot(), path)
	}


	BuildConfiguration getParent(BuildConfiguration buildSettings) {
		BuildConfiguration result = buildSettings
		File infoPlist = new File(project.projectDir, buildSettings.infoplist);
		String bundleIdentifier = plistHelper.getValueFromPlist(infoPlist, "WKCompanionAppBundleIdentifier")
		if (bundleIdentifier != null) {

			projectSettings.each { String key, BuildTargetConfiguration buildConfiguration ->

				BuildConfiguration settings = buildConfiguration.buildSettings[configuration];
				if (settings != null && settings.bundleIdentifier.equalsIgnoreCase(bundleIdentifier)) {
					result = settings
					return
				}
			}
		}
		return result;

	}


	File getApplicationBundle() {

		BuildTargetConfiguration buildConfiguration = projectSettings[target]
		if (buildConfiguration != null) {
			BuildConfiguration buildSettings = buildConfiguration.buildSettings[configuration];
			if (buildSettings != null && buildSettings.sdkRoot.equalsIgnoreCase("watchos")) {
				BuildConfiguration parent = getParent(buildSettings)
				return new File(getOutputPath(), parent.productName + "." + this.productType)
			}
		}
		return new File(getOutputPath(), getBundleName() + "." + this.productType)
	}

	File getBinary() {
		logger.debug("getBinary")
		BuildTargetConfiguration buildConfiguration = projectSettings[target]
		if (buildConfiguration != null) {
			BuildConfiguration buildSettings = buildConfiguration.buildSettings[configuration];
			logger.debug("buildSettings: {}", buildSettings)
			if (type == Type.OSX) {
				return new File(getOutputPath(), buildSettings.productName + ".app/Contents/MacOS/" + buildSettings.productName)
			}
			return new File(getOutputPath(), buildSettings.productName + ".app/" + buildSettings.productName)
		}
		return null
	}


	BuildConfiguration getBuildConfiguration() {
		BuildTargetConfiguration buildTargetConfiguration = projectSettings[target]
		if (buildTargetConfiguration != null) {
			return buildTargetConfiguration.buildSettings[configuration];
		}
		throw new IllegalStateException("No build configuration found for + target '" + parameters.target + "' and configuration '" + configuration + "'");
	}

	BuildConfiguration getBuildConfiguration(String bundleIdentifier) {
		BuildConfiguration result = null
		projectSettings.each() { target, buildTargetConfiguration ->
			BuildConfiguration settings = buildTargetConfiguration.buildSettings[configuration];

			if (settings != null) {

				if (settings.bundleIdentifier == null) {
					String identifier = plistHelper.getValueFromPlist(settings.infoplist, "CFBundleIdentifier")
					if (identifier != null && identifier.equalsIgnoreCase(bundleIdentifier)) {
						result = settings
						return true
					}
				} else if (settings.bundleIdentifier.equalsIgnoreCase(bundleIdentifier)) {
					result = settings
					return true
				}
			}
		}
		return result
	}




	void setType(String type) {
		this.type = Type.typeFromString(type);
	}


	void setSdk(String sdk) {
		throw new IllegalArgumentException("Settings the 'sdk' is not supported anymore. Use the 'type' parameter instead")
	}


	boolean getSimulator() {
		if (type == Type.OSX) {
			return false
		}
		return this.simulator
	}

	void setSimulator(Object simulator) {
		if (simulator instanceof Boolean) {
			this.simulator = simulator
			return
		}
		this.simulator = simulator.toString().equalsIgnoreCase("true") || simulator.toString().equalsIgnoreCase("yes")
	}

	void setProjectFile(def projectFile) {
		if (projectFile instanceof File) {
			this.projectFile = projectFile
		}
		this.projectFile = new File(projectFile)
	}

	File getProjectFile() {
		if (this.projectFile != null) {
			return this.projectFile
		}
		return new File(project.projectDir, project.projectDir.list(new SuffixFileFilter(".xcodeproj"))[0])
	}

	// should be remove in the future, so that every task has its own xcode object
	Xcode getXcode() {
		if (xcode == null) {
			xcode = new Xcode(commandRunner, xcodeVersion)
		}
 		return xcode
	}


	SimulatorControl getSimulatorControl() {
		if (simulatorControl == null) {
			simulatorControl = new SimulatorControl(project, commandRunner, getXcode())
		}
		return simulatorControl
	}


	XcodebuildParameters getXcodebuildParameters() {
		return new XcodebuildParameters(this)
	}

}