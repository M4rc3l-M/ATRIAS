package io.github.agentsoz.ees.Run;

/*-
 * #%L
 * Emergency Evacuation Simulator
 * %%
 * Copyright (C) 2014 - 2025 by its authors. See AUTHORS file.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */


import io.github.agentsoz.bdiabm.v2.AgentDataContainer;
import io.github.agentsoz.bdiabm.v3.QueryPerceptInterface;
import io.github.agentsoz.dataInterface.DataClient;
import io.github.agentsoz.dataInterface.DataServer;
import io.github.agentsoz.ees.shared.Cells;
import io.github.agentsoz.ees.shared.SharedConstants;
import io.github.agentsoz.ees.matsim.*;
import io.github.agentsoz.ees.util.EventTracker;
import io.github.agentsoz.ees.util.Parser;
import io.github.agentsoz.ees.util.Utils;
import io.github.agentsoz.util.Global;
import io.github.agentsoz.util.Time;
import org.matsim.api.core.v01.Scenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

import java.util.*;
/**
 * Emergency Evacuation Simulator (EES) main program.
 * @author Dhirendra Singh
 */
public class Run implements DataClient {

    private static final Logger log = LoggerFactory.getLogger(Run.class);
    public static final String DATASERVER = "ees";
    private final Map<String, DataClient> dataListeners = createDataListeners();
    private AgentDataContainer adc_from_bdi = new AgentDataContainer();
    private AgentDataContainer adc_from_abm = new AgentDataContainer();
    private final Object sequenceLock = new Object();

    // Models
    DataServer dataServer = null;

    //  Defaults
    private double optTimestep = 2;


    public static void main(String[] args) {
        EventTracker.removeOldEvents();
        Thread.currentThread().setName("ees");
        XMLConfig xmlConfig = new XMLConfig();
        String configPath = System.getenv("ConfigFile");
        Element xmlConfigRoot = Parser.parseXML(configPath);
        xmlConfig.applyConfig(xmlConfigRoot);
        Cells.applyConfig(configPath);
        SharedConstants.configure();


        args = xmlConfig.setArgs(xmlConfigRoot);

        // Read the config
        Config cfg = new Config();
        Map<String,String> opts = cfg.parse(args);
        cfg.loadFromFile(opts.get(Config.OPT_CONFIG));

        // Get BDI agents map from the MATSim population file
        log.info("Reading BDI agents from MATSim population file");
        Map<Integer, List<String[]>> bdiMap = Utils.getAgentsFromMATSimPlansFile(cfg.getModelConfig(Config.eModelMatsim).get("configXml"));

        // Run it
        new Run()
                .withModel(DataServer.getInstance(DATASERVER))
                .start(cfg, bdiMap);

    }

    public void start(Config cfg, Map<Integer, List<String[]>> bdiMap){
        parse(cfg.getModelConfig(""));

        log.info("Starting the data server");
        // initialise the data server bus for passing data around using a publish/subscribe or pull mechanism
        if (dataServer == null) {
            dataServer = DataServer.getInstance(DATASERVER);
        }
        dataServer.setTime(hhMmToS(cfg.getGlobalConfig(Config.eGlobalStartHhMm)));
        dataServer.setTimeStep(optTimestep);
        dataServer.subscribe(this, Constants.AGENT_DATA_CONTAINER_FROM_BDI);

        // initialise the fire model and register it as an active data source

        // initialise the MATSim model and register it as an active data source
        log.info("Creating MATSim model");
        MATSimEvacModel matsimEvacModel = new MATSimEvacModel(cfg.getModelConfig(Config.eModelMatsim), dataServer);
        matsimEvacModel.loadAndPrepareConfig();
        EvacConfig evacConfig = matsimEvacModel.getEvacConfig();
        Scenario scenario = matsimEvacModel.loadAndPrepareScenario() ;

        // initialise the diffusion model and register it as an active data source
        log.info("Starting information diffusion model");



        // initialise the Jadex model, register it as an active data source, and start it
        log.info("Starting Jadex BDI model");

        Object[] requiredBDIinfo = JadexModel.extractXMLData();
        JadexModel jadexmodel = new JadexModel(cfg.getModelConfig(Config.eModelBdi), dataServer, (QueryPerceptInterface)matsimEvacModel);
        jadexmodel.setAgentDataContainer(adc_from_bdi);
        jadexmodel.init(requiredBDIinfo);
        jadexmodel.start();


        //--- DeckGL event writer


        // --- initialize and start MATSim
        log.info("Starting MATSim model");
        System.out.println("MY PRINT " + bdiMap.keySet());
        matsimEvacModel.setAgentDataContainer(adc_from_abm);
        matsimEvacModel.init(new Object[]{Arrays.asList(Utils.getAsSortedStringArray(bdiMap.keySet()))});

        matsimEvacModel.start();



        // start the main simulation loop
        log.info("Starting the simulation loop");
        jadexmodel.useSequenceLock(sequenceLock);
        matsimEvacModel.useSequenceLock(sequenceLock);

        while (true){
        synchronized (sequenceLock) {
            dataServer.stepTime();
        }

        synchronized (sequenceLock) {
            if (matsimEvacModel.isFinished()) {
                break;

            }
        }

        JadexModel.inBDIcycle = false;
        // ABM to take control; the ABM thread should synchronize on adc_from_abm
        dataServer.publish(Constants.TAKE_CONTROL_ABM, adc_from_bdi);
        // BDI to take control; the BDI thread should synchronize on adc_from_bdi
        JadexModel.inBDIcycle = true;
        dataServer.publish(Constants.TAKE_CONTROL_BDI, adc_from_abm);
        JadexModel.inBDIcycle = false;
        }
        // finish up
        log.info("Finishing up");
        matsimEvacModel.finish() ;

        DataServer.cleanup() ;
        jadexmodel.finish();
        log.info("All done");
    }


    private void parse(Map<String, String> opts) {
        if (opts == null) {
            return;
        }
        for (String opt : opts.keySet()) {
            log.info("Found option: {}={}", opt, opts.get(opt));
            switch(opt) {
                case Config.eGlobalRandomSeed:
                    Global.setRandomSeed(Long.parseLong(opts.get(opt)));
                    break;
                case Config.eGlobalTimeStep:
                    optTimestep = Integer.parseInt(opts.get(opt));
                    break;
                default:
                    log.warn("Ignoring option: " + opt + "=" + opts.get(opt));
            }
        }
    }

    /**
     * Convert HHMM string to seconds
     * @param HHMM time in HHMM format
     * @return time in seconds
     */
    private static double hhMmToS(String HHMM) {
        String[] tokens = HHMM.split(":");
        int[] hhmm = new int[]{Integer.parseInt(tokens[0]),Integer.parseInt(tokens[1])};
        double secs = Time.convertTime(hhmm[0], Time.TimestepUnit.HOURS, Time.TimestepUnit.SECONDS)
                + Time.convertTime(hhmm[1], Time.TimestepUnit.MINUTES, Time.TimestepUnit.SECONDS);
        return secs;
    }

    /**
     * Allows models to be overriden; interim solution for #37
     * @param model the model to override with
     * @return the run object
     */
    public Run withModel(Object model) {
        if (model != null) {
            if (model instanceof DataServer) {
                this.dataServer = (DataServer) model;

            }

            else {
                throw new RuntimeException(
                        "Not all models can be overriden in this way. " +
                                " A cleaner mechanism is under development, " +
                                "see https://github.com/agentsoz/ees/issues/37"
                );
            }
        }
        return this;
    }

    private Map<String, DataClient> createDataListeners() {
        Map<String, DataClient> listeners = new  HashMap<>();

        // Saves the incoming agent data container from BDI
        listeners.put(Constants.AGENT_DATA_CONTAINER_FROM_BDI, (DataClient<AgentDataContainer>) (time, dataType, data) -> {
            adc_from_bdi = data;
        });

        // Saves the incoming agent data container from the ABM
        listeners.put(Constants.AGENT_DATA_CONTAINER_FROM_ABM, (DataClient<AgentDataContainer>) (time, dataType, data) -> {
            adc_from_abm = data;
        });
        return listeners;
    }

    @Override
    public void receiveData(double time, String dataType, Object data) {
        switch (dataType) {
            case Constants.AGENT_DATA_CONTAINER_FROM_BDI:
            case Constants.AGENT_DATA_CONTAINER_FROM_ABM:
                dataListeners.get(dataType).receiveData(time, dataType, data);
                break;
            default:
                throw new RuntimeException("Unknown data type received: " + dataType);
        }
    }
}
