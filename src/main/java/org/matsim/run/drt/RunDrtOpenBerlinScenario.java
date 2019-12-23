/* *********************************************************************** *
 * project: org.matsim.*
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2017 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.matsim.run.drt;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.contrib.av.robotaxi.fares.drt.DrtFareConfigGroup;
import org.matsim.contrib.av.robotaxi.fares.drt.DrtFareModule;
import org.matsim.contrib.av.robotaxi.fares.drt.DrtFaresConfigGroup;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.contrib.drt.routing.DrtRouteFactory;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.DrtConfigs;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtModule;
import org.matsim.contrib.dvrp.run.DvrpConfigGroup;
import org.matsim.contrib.dvrp.run.DvrpModule;
import org.matsim.contrib.dvrp.run.DvrpQSimComponents;
import org.matsim.core.config.CommandLine;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.population.routes.RouteFactories;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt.transitSchedule.api.TransitStopFacility;
import org.matsim.run.RunBerlinScenario;
import org.matsim.run.drt.ptRoutingModes.PtIntermodalRoutingModesConfigGroup;
import org.matsim.run.drt.ptRoutingModes.PtIntermodalRoutingModesModule;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;

/**
 * This class starts a simulation run with DRT.
 * 
 *  - The input DRT vehicles file specifies the number of vehicles and the vehicle capacity (a vehicle capacity of 1 means there is no ride-sharing).
 * 	- The DRT service area is set to the the inner-city Berlin area (see input shape file).
 * 	- Initial plans are not modified.
 * 
 * @author ikaddoura
 */

public final class RunDrtOpenBerlinScenario {

	private static final Logger log = Logger.getLogger(RunDrtOpenBerlinScenario.class);
	
	private static final String DRT_ACCESS_EGRESS_TO_PT_STOP_FILTER_ATTRIBUTE = "drtStopFilter";
	private static final String DRT_ACCESS_EGRESS_TO_PT_STOP_FILTER_VALUE = "station_S/U/RE/RB_drtServiceArea";
	public static final String DRT_ACCESS_EGRESS_TO_PT_PERSON_FILTER_ATTRIBUTE = "canUseDrt";
	public static final String DRT_ACCESS_EGRESS_TO_PT_PERSON_FILTER_VALUE = "true";
	public static final String ROUTING_MODE_PT_WITH_DRT_ENABLED_FOR_ACCESS_EGRESS = "pt_w_drt";

	public static void main(String[] args) throws CommandLine.ConfigurationException {
		
		for (String arg : args) {
			log.info( arg );
		}
		
		if ( args.length==0 ) {
			args = new String[] {"scenarios/berlin-v5.5-1pct/input/drt/berlin-drt-v5.5-1pct.config.xml"}  ;
		}
		
		Config config = prepareConfig( args ) ;
		Scenario scenario = prepareScenario( config ) ;
		Controler controler = prepareControler( scenario ) ;
		controler.run() ;
	}
	
	public static Controler prepareControler( Scenario scenario ) {

		Controler controler = RunBerlinScenario.prepareControler( scenario ) ;
		
		// drt + dvrp module
		controler.addOverridingModule(new MultiModeDrtModule());
		controler.addOverridingModule(new DvrpModule());
		controler.configureQSimComponents(DvrpQSimComponents.activateAllModes(MultiModeDrtConfigGroup.get(controler.getConfig())));
		
		controler.addOverridingModule(new AbstractModule() {
			
			@Override
			public void install() {
				// use a main mode identifier which knows how to handle intermodal trips generated by the used sbb pt raptor router
				// the SwissRailRaptor already binds its IntermodalAwareRouterModeIdentifier, however drt obviuosly replaces it
				// with its own implementation
				// So we need our own main mode indentifier which replaces both :-(
				bind(MainModeIdentifier.class).to(OpenBerlinIntermodalPtDrtRouterModeIdentifier.class);
				bind(AnalysisMainModeIdentifier.class).to(OpenBerlinIntermodalPtDrtRouterAnalysisModeIdentifier.class);
			}
		});

		// Add drt-specific fare module
		controler.addOverridingModule(new DrtFareModule());
		// yyyy there is fareSModule (with S) in config. ?!?!  kai, jul'19
		
		Map<String, Double> drtMode2Compensation = new HashMap<>();
		Set<String> ptModes = new HashSet<>();
		
		ptModes.add(TransportMode.pt);
		
		DrtFaresConfigGroup drtFaresCfg = ConfigUtils.addOrGetModule(controler.getConfig(), DrtFaresConfigGroup.class);
		
		for (DrtFareConfigGroup drtFareCfg: drtFaresCfg.getDrtFareConfigGroups()) {
			// TODO: make configurable
			// effectively compensate min fare - fare for 1 km (otherwise to many users?)
			double compensation = drtFareCfg.getMinFarePerTrip() - drtFareCfg.getDistanceFare_m() * 1000;
			drtMode2Compensation.put(drtFareCfg.getMode(), compensation); // here min fare, not base fare
			drtMode2Compensation.put(drtFareCfg.getMode() + "_teleportation", compensation); // drt speed up mode
		}
		
		controler.addOverridingModule(new AbstractModule() {
			
			@Override
			public void install() {
				addEventHandlerBinding().toInstance(new PtDrtIntermodalTripDrtFareCompensator(drtMode2Compensation, ptModes));		
			}
		});
		
		controler.addOverridingModule(new PtIntermodalRoutingModesModule());
		
		return controler;
	}
	
	public static Scenario prepareScenario( Config config ) {

		Scenario scenario = RunBerlinScenario.prepareScenario( config );

		RouteFactories routeFactories = scenario.getPopulation().getFactory().getRouteFactories();
		routeFactories.setRouteFactory(DrtRoute.class, new DrtRouteFactory());

		for (DrtConfigGroup drtCfg : MultiModeDrtConfigGroup.get(config).getModalElements()) {
			
			String drtServiceAreaShapeFile = drtCfg.getDrtServiceAreaShapeFile();
			if (drtServiceAreaShapeFile != null && !drtServiceAreaShapeFile.equals("") && !drtServiceAreaShapeFile.equals("null")) {
				addDRTmode(scenario, drtCfg.getMode(), drtServiceAreaShapeFile);
				tagTransitStopsInServiceArea(scenario.getTransitSchedule(), 
						DRT_ACCESS_EGRESS_TO_PT_STOP_FILTER_ATTRIBUTE, DRT_ACCESS_EGRESS_TO_PT_STOP_FILTER_VALUE, 
						drtServiceAreaShapeFile,
						"stopFilter", "station_S/U/RE/RB",
						// some S+U stations are located slightly outside the shp File, e.g. U7 Neukoelln, U8
						// Hermannstr., so allow buffer around the shape.
						// This does not mean that a drt vehicle can pick the passenger up outside the service area,
						// rather the passenger has to walk the last few meters from the drt drop off to the station.
						200.0); // TODO: Use constant in RunGTFS2MATSimOpenBerlin and here? Or better some kind of set available pt modes?
			}
		}
		
		return scenario;
	}
	
	public static Config prepareConfig( String [] args, ConfigGroup... customModules) {
		ConfigGroup[] customModulesToAdd = new ConfigGroup[]{new DvrpConfigGroup(), new MultiModeDrtConfigGroup(), new DrtFaresConfigGroup(), new SwissRailRaptorConfigGroup(), new PtIntermodalRoutingModesConfigGroup() };
		ConfigGroup[] customModulesAll = new ConfigGroup[customModules.length + customModulesToAdd.length];
		
		int counter = 0;
		for (ConfigGroup customModule : customModules) {
			customModulesAll[counter] = customModule;
			counter++;
		}
		
		for (ConfigGroup customModule : customModulesToAdd) {
			customModulesAll[counter] = customModule;
			counter++;
		}

		Config config = RunBerlinScenario.prepareConfig( args, customModulesAll ) ;
		
		// switch off pt vehicle simulation: very slow, because also switches from Raptor to the old pt router
//		config.transit().setUsingTransitInMobsim(false);

		DrtConfigs.adjustMultiModeDrtConfig(MultiModeDrtConfigGroup.get(config), config.planCalcScore(), config.plansCalcRoute());

		return config ;
	}
	
	public static void addDRTmode(Scenario scenario, String drtNetworkMode, String drtServiceAreaShapeFile) {
		
		log.info("Adjusting network...");
		
		BerlinShpUtils shpUtils = new BerlinShpUtils( drtServiceAreaShapeFile );

		int counter = 0;
		int counterInside = 0;
		int counterOutside = 0;
		for (Link link : scenario.getNetwork().getLinks().values()) {
			if (counter % 10000 == 0)
				log.info("link #" + counter);
			counter++;
			if (link.getAllowedModes().contains(TransportMode.car)) {
				if (shpUtils.isCoordInDrtServiceArea(link.getFromNode().getCoord())
						|| shpUtils.isCoordInDrtServiceArea(link.getToNode().getCoord())) {
					Set<String> allowedModes = new HashSet<>(link.getAllowedModes());
					
					allowedModes.add(drtNetworkMode);

					link.setAllowedModes(allowedModes);
					counterInside++;
				} else {
					counterOutside++;
				}

			} else if (link.getAllowedModes().contains(TransportMode.pt)) {
				// skip pt links
			} else {
				throw new RuntimeException("Aborting...");
			}
		}
		
		log.info("Total links: " + counter);
		log.info("Total links inside service area: " + counterInside);
		log.info("Total links outside service area: " + counterOutside);
		
		Set<String> modes = new HashSet<>();
		modes.add(drtNetworkMode);
		new MultimodalNetworkCleaner(scenario.getNetwork()).run(modes);
	}
	
	private static void tagTransitStopsInServiceArea(TransitSchedule transitSchedule, 
			String newAttributeName, String newAttributeValue, 
			String drtServiceAreaShapeFile, 
			String oldFilterAttribute, String oldFilterValue,
			double bufferAroundServiceArea) {
		BerlinShpUtils shpUtils = new BerlinShpUtils( drtServiceAreaShapeFile );
		for (TransitStopFacility stop: transitSchedule.getFacilities().values()) {
			if (stop.getAttributes().getAttribute(oldFilterAttribute) != null) {
				if (stop.getAttributes().getAttribute(oldFilterAttribute).equals(oldFilterValue)) {
					if (shpUtils.isCoordInDrtServiceAreaWithBuffer(stop.getCoord(), bufferAroundServiceArea)) {
						stop.getAttributes().putAttribute(newAttributeName, newAttributeValue);
					}
				}
			}
		}
	}

}

