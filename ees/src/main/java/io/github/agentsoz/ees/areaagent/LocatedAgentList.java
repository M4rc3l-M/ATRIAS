package io.github.agentsoz.ees.areaagent;

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


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import io.github.agentsoz.util.Location;

public class LocatedAgentList {

    public final List<LocatedAgent> LocatedAgentList = Collections.synchronizedList(new ArrayList<>());

    private AreaAgent areaAgent;

    public int size(){
        return LocatedAgentList.size();
    }

    public void updateLocatedAgentList(LocatedAgent agent, long timestamp, String action){
        switch (action){
            case "register": {
                LocatedAgentList.add(agent);

                synchronized (areaAgent.loadLock){
                    if(areaAgent.getLoad() >= AreaConstants.NO_TRIKES_NO_TRIPS_LOAD){
                        double newLoad = areaAgent.getLoad() - AreaConstants.NO_TRIKES_NO_TRIPS_LOAD;
                        areaAgent.setLoad(newLoad);
                    }
                    else{
                        double newLoad = areaAgent.getLoad() * ((size() - 1.0) / size());
                        areaAgent.setLoad(newLoad);
                    }
                }

                areaAgent.lastDelegateRequestTS = -1;
                break;
            }
            case "update": {
                synchronized (LocatedAgentList){
                    for (LocatedAgent locatedAgent : LocatedAgentList) {
                        if (agent.getAgentID().equals(locatedAgent.getAgentID())) {
                            locatedAgent.updateLocatedAgent(agent.getLastPosition(), timestamp);
                        }
                    }
                }
               break;
            }
            case "deregister": {
                synchronized (LocatedAgentList){
                    LocatedAgentList.removeIf(locatedAgent -> locatedAgent.getAgentID().equals(agent.getAgentID()));
                    synchronized (areaAgent.loadLock){
                        if(size() == 0){
                            double newLoad = areaAgent.getLoad() + AreaConstants.NO_TRIKES_NO_TRIPS_LOAD;
                            areaAgent.setLoad(newLoad);
                        }else{
                            double newLoad = areaAgent.getLoad() * ((size() + 1.0) / size());
                            areaAgent.setLoad(newLoad);
                        }
                    }
                    areaAgent.lastDelegateRequestTS = -1;
                }
                break;
            }
        }
    }

    public String calculateClosestLocatedAgent(Location startPosition){
        String closestAgentID = null;

        double lowestDistance = Double.MAX_VALUE;
        double compareDistance;

        synchronized (LocatedAgentList){
            for (LocatedAgent toInvestigate: LocatedAgentList){
                compareDistance = Location.distanceBetween(startPosition, toInvestigate.getLastPosition());
                if (compareDistance<lowestDistance){
                    lowestDistance = compareDistance;
                    closestAgentID = toInvestigate.getAgentID();

                }
            }
        }
        return closestAgentID;
    }

    public void setAreaAgent(AreaAgent areaAgent){
        this.areaAgent = areaAgent;
    }
}
