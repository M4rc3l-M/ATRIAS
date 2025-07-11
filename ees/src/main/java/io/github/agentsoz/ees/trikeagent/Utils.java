package io.github.agentsoz.ees.trikeagent;

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

import com.google.api.core.ApiFuture;
import com.google.common.collect.Lists;
import com.google.firebase.database.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import io.github.agentsoz.bdiabm.v3.AgentNotFoundException;
import io.github.agentsoz.ees.Run.Constants;
import io.github.agentsoz.ees.firebase.FirebaseHandler;
import io.github.agentsoz.ees.gui.util.DataDeserializer;
import io.github.agentsoz.ees.shared.*;
import io.github.agentsoz.ees.JadexService.AreaTrikeService.IAreaTrikeService;
import io.github.agentsoz.ees.JadexService.NotifyService2.INotifyService2;
import io.github.agentsoz.ees.Run.JadexModel;
import io.github.agentsoz.ees.simagent.SimIDMapper;
import io.github.agentsoz.ees.util.*;
import io.github.agentsoz.util.Location;
import io.github.agentsoz.util.PerceptList;
import jadex.bridge.service.ServiceScope;
import jadex.bridge.service.search.ServiceQuery;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static io.github.agentsoz.ees.shared.SharedConstants.FIREBASE_ENABLED;
import static io.github.agentsoz.ees.trikeagent.TrikeConstants.*;
import static io.github.agentsoz.ees.JadexService.AreaTrikeService.IAreaTrikeService.messageToService;


public class Utils {

    public static final Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeDeserializer())
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeSerializer())
            .registerTypeAdapter(Data.class, new DataDeserializer())
            .setPrettyPrinting()
            .create();

    private final TrikeAgent trikeAgent;

    public Utils(TrikeAgent trikeAgent){
        this.trikeAgent = trikeAgent;
    }

    //////////////////////////////////////////////////
    //  JSON LOGGER
    public EventTracker eventTracker = new EventTracker();

    //////////////////////////////////////////////////

    public Location getNextChargingStation(){
        //CHARGING_STATION_LIST
        Location ChargingStation = CHARGING_STATION_LIST.get(0); //= new Location("", 476530.26535798033, 5552438.979076344);
        // last trip In pipe endlocation oder agentposition als ausgang nehmen
        Location startPosition;
        if (trikeAgent.tripList.size() == 0 && trikeAgent.currentTrip.size() == 0){
            startPosition = trikeAgent.agentLocation;
        }
        else {
            synchronized (trikeAgent.tripList){
                startPosition = getLastTripInPipeline().getEndPosition();
            }
        }
        double lowestDistance = Double.MAX_VALUE;
        for (Location location : CHARGING_STATION_LIST) {
            double compareDistance = getDistanceBetween(startPosition, location);
            if (compareDistance < lowestDistance) {
                lowestDistance = compareDistance;
                ChargingStation = location;
            }
        }

        return ChargingStation;
    }

    //test if there is at least one trip anywhere
    public Trip getLastTripInPipeline(){
        Trip lastTrip = null;
        if (trikeAgent.tripList.size()>0){
            lastTrip = trikeAgent.tripList.get(trikeAgent.tripList.size()-1);
        }
        else if (trikeAgent.currentTrip.size()>0){
            lastTrip = trikeAgent.currentTrip.get(trikeAgent.currentTrip.size()-1);

        }
        else{
            System.out.println("ERROR: getLastTripInPipeline() no trips available!");
        }
        return lastTrip;
    }

    //estimates the batteryLevel after all Trips. Calculations a based on aerial line x1.5
    public Double estimateBatteryAfterTIP(){
        double batteryChargeAfterTIP = trikeAgent.trikeBattery.getMyChargestate();
        double totalDistance_TIP = 0.0;
        synchronized (trikeAgent.tripList){
            if (trikeAgent.currentTrip.size() == 1) { //battery relavant distance driven at trikeAgent.currentTrip
                //todo: fortschritt von trikeAgent.currentTrip berücksichtigen
                totalDistance_TIP += getDistanceBetween(trikeAgent.agentLocation, trikeAgent.currentTrip.get(0).getStartPosition());
                if (trikeAgent.currentTrip.get(0).getTripType().equals("CustomerTrip")) { //only drive to the end when it is a customerTrip
                    totalDistance_TIP += getDistanceBetween(trikeAgent.currentTrip.get(0).getStartPosition(), trikeAgent.currentTrip.get(0).getEndPosition());
                }
                if (trikeAgent.currentTrip.get(0).getTripType().equals("ChargingTrip")) {
                    totalDistance_TIP = 0.0; //reset the distance until now because only the distance after a chargingTrip influences the battery
                }
            }
            // battery relavant distance driven at trikeAgent.tripList
            if (trikeAgent.tripList.size() > 0) {
                if (trikeAgent.currentTrip.size() > 0) { //journey to the first entry in the trikeAgent.tripList from a trikeAgent.currentTrip
                    if (trikeAgent.currentTrip.get(0).getTripType().equals("CustomerTrip")) {
                        totalDistance_TIP += getDistanceBetween(trikeAgent.currentTrip.get(0).getEndPosition(), trikeAgent.tripList.get(0).getStartPosition());
                    } else { // trips with only a start position
                        totalDistance_TIP += getDistanceBetween(trikeAgent.currentTrip.get(0).getStartPosition(), trikeAgent.tripList.get(0).getStartPosition());
                    }
                } else { //journey to the first entry in the trikeAgent.tripList from the trikeAgent.agentLocation
                    totalDistance_TIP += getDistanceBetween(trikeAgent.agentLocation, trikeAgent.tripList.get(0).getStartPosition());
                }
                // distance driven at trikeAgent.tripList.get(0)
                if (trikeAgent.tripList.get(0).getTripType().equals("CustomerTrip")) {
                    totalDistance_TIP += getDistanceBetween(trikeAgent.tripList.get(0).getStartPosition(), trikeAgent.tripList.get(0).getEndPosition());
                }
                if (trikeAgent.tripList.get(0).getTripType().equals("ChargingTrip")) {
                    totalDistance_TIP = 0.0;
                } else {
                    // do nothing as all other Trips with only a startPosition will not contain any other movements;
                }

                //todo: fahrt zum nächjsten start fehlt +-1 bei i???
                // interates through all other Trips inside trikeAgent.tripList
                if (trikeAgent.tripList.size() > 1){ //added to avoid crashes
                    for (int i = 1; i < trikeAgent.tripList.size(); i++) {
                        if (trikeAgent.tripList.get(i - 1).getTripType().equals("CustomerTrip")) {
                            totalDistance_TIP += getDistanceBetween(trikeAgent.tripList.get(i - 1).getEndPosition(), trikeAgent.tripList.get(i).getStartPosition()); //trikeAgent.tripList or trikeAgent.currentTrip
                        } else { // Trips with only a startPosition
                            totalDistance_TIP += getDistanceBetween(trikeAgent.tripList.get(i - 1).getStartPosition(), trikeAgent.tripList.get(i).getStartPosition()); //corrected! was to EndPosition before!
                        }
                        if (trikeAgent.tripList.get(i).getTripType().equals("CustomerTrip")) {
                            totalDistance_TIP += getDistanceBetween(trikeAgent.tripList.get(i).getStartPosition(), trikeAgent.tripList.get(i).getEndPosition());
                        }
                    }
                }
            }
            Double estEnergyConsumption_TIP = trikeAgent.trikeBattery.SimulateDischarge((totalDistance_TIP * DISTANCE_FACTOR));
            batteryChargeAfterTIP = batteryChargeAfterTIP - estEnergyConsumption_TIP;

            trikeAgent.estimateBatteryAfterTIP.set(0, batteryChargeAfterTIP);

            eventTracker.estimateBatteryAfterTIP_BeliefUpdated(trikeAgent, batteryChargeAfterTIP);
        }


        return batteryChargeAfterTIP;
    }


    public void selectNextAction(Iterator<DecisionTask> iterator){
        boolean hasChanged;
        DecisionTask currentDecisionTask = iterator.next();
        int iterations = 0;
        do{
            switch (currentDecisionTask.getStatus()) {
                case NEW: {
                    //  Execute Utillity here > "commit"|"delegate"
                    Double ownScore = calculateUtility(currentDecisionTask);
                    currentDecisionTask.setUtilityScore(trikeAgent.agentID, ownScore);

                    if (ownScore < commitThreshold && CNP_ACTIVE) {
                        currentDecisionTask.setStatus(DecisionTask.Status.DELEGATE);
                        Location jobLocation = currentDecisionTask.getJob().getStartPosition();
                        currentDecisionTask.cell = Cells.locationToCellAddress(jobLocation, Cells.getCellResolution(trikeAgent.cell));

                        //  isLocal true, if the trip starts in the cell of the current area
                        currentDecisionTask.isLocal = trikeAgent.cell.equals(currentDecisionTask.cell);
                    } else {
                        eventTracker.DecisionTaskCommit(trikeAgent, currentDecisionTask);

                        currentDecisionTask.setStatus(DecisionTask.Status.COMMIT);
                        String timeStampBooked = new SimpleDateFormat("HH.mm.ss.ms").format(new java.util.Date());
                        System.out.println("FINISHED Negotiation - JobID: " + currentDecisionTask.getJobID() + " TimeStamp: " + timeStampBooked);
                    }

                    long delta = (SharedUtils.getSimTime() - SharedUtils.getTimeStamp(currentDecisionTask.getJob().getVATime())) / 1000;
                    System.out.println("NEW " + currentDecisionTask.getJob().getID() + ": " + currentDecisionTask.getOrigin() + " " +
                            delta);

                    hasChanged = true;
                    break;
                }
                case COMMIT: {
                    Trip newTrip = new Trip(currentDecisionTask, currentDecisionTask.getJobID(), "CustomerTrip",
                            currentDecisionTask.getVATimeFromJob(), currentDecisionTask.getStartPositionFromJob(),
                            currentDecisionTask.getEndPositionFromJob(), "NotStarted");

                    LocalDateTime prevEndTime;
                    double distToCustomer;
                    double distToEnd;

                    if (!trikeAgent.tripList.isEmpty()) {
                        int lastTripIndex = trikeAgent.tripList.size() - 1;
                        Trip lastTrip = trikeAgent.tripList.get(lastTripIndex);

                        distToCustomer = getDistanceBetween(lastTrip.getEndPosition(), newTrip.getStartPosition());
                        distToEnd = getDistanceBetween(newTrip.getStartPosition(), newTrip.getEndPosition());
                        prevEndTime = lastTrip.getEndTime();
                    } else if(!trikeAgent.currentTrip.isEmpty()){
                        Trip lastTrip = trikeAgent.currentTrip.get(0);

                        distToCustomer = getDistanceBetween(lastTrip.getEndPosition(), newTrip.getStartPosition());
                        distToEnd = getDistanceBetween(newTrip.getStartPosition(), newTrip.getEndPosition());
                        prevEndTime = lastTrip.getEndTime();
                    } else{
                        distToCustomer = getDrivingDistanceTo(newTrip.getStartPosition());
                        distToEnd = getDistanceBetween(newTrip.getStartPosition(), newTrip.getEndPosition());
                        prevEndTime = SharedUtils.getCurrentDateTime();
                    }

                    long drivingTimeInSec = (long) ((((distToCustomer + distToEnd) / 1000) / DRIVING_SPEED)*60*60);

                    newTrip.setEndTime(prevEndTime.plusSeconds(drivingTimeInSec));

                    synchronized (trikeAgent.tripList){
                        trikeAgent.tripList.removeIf(trip -> trip.getTripID().startsWith("area"));
                        trikeAgent.tripList.add(newTrip);
                    }
                    eventTracker.TripList_BeliefUpdated(trikeAgent);
                    eventTracker.CustomerTripCreation(trikeAgent, newTrip);


                    Location destination = currentDecisionTask.getEndPositionFromJob();
                    String destinationCell = Cells.findKey(destination);
                    boolean isInArea = trikeAgent.cell.equals(destinationCell);

                    if(!isInArea && destinationCell != null){
                        String originArea = Cells.cellAgentMap.get(trikeAgent.cell);
                        String newArea = Cells.cellAgentMap.get(destinationCell);
                        trikeAgent.cell = destinationCell;
                        changeArea(originArea, newArea);
                    }

                    currentDecisionTask.setStatus(DecisionTask.Status.COMMITTED);
                    long delta = (SharedUtils.getSimTime() - SharedUtils.getTimeStamp(newTrip.getVATime())) / 1000;

                    System.out.println(trikeAgent.getAgentID() + " COMMITTED " + newTrip.getTripID() + ": " + currentDecisionTask.getOrigin() + " " +
                            delta + " sec delay!");


                    if(FIREBASE_ENABLED){
                        //  listen to the new child in firebase
                        ChildEventListener childEventListener = trikeAgent.firebaseHandler.childAddedListener("trips/"+newTrip.tripID+"/messages", (dataSnapshot, previousChildName, list)->{
                            System.out.println(dataSnapshot);
                            String sender = (String) dataSnapshot.child("sender").getValue();
                            if(sender.equals("agent")) return;

                            // Iterate through messages under this trip
                            String message = (String) dataSnapshot.child("message").getValue();

                            // Check if the message is the specific question
                            switch (message){
                                case "How many trips are scheduled before mine?": {
                                    DatabaseReference parent = dataSnapshot.getRef().getParent();
                                    String tripId = parent.getKey();
                                    int numberOfTrips = 0;
                                    System.out.println(list);
                                    for(int i = 0; i < list.size(); i++){
                                        if(list.get(i).getTripID().equals(tripId)){
                                            numberOfTrips = i;
                                            break;
                                        }
                                    }

                                    // Push a new message node under the 'messages' node
                                    DatabaseReference newMessageRef = dataSnapshot.getRef().getParent().push();
                                    // Set the message content
                                    newMessageRef.child("message").setValueAsync("Number of trips before yours: " + numberOfTrips);
                                    // Add other necessary fields, e.g., sender and timestamp if needed
                                    newMessageRef.child("sender").setValueAsync("agent");
                                    // Remove the question message from Firebase
                                    /*
                                    messageSnapshot.getRef().removeValue(new DatabaseReference.CompletionListener() {
                                        @Override
                                        public void onComplete(DatabaseError databaseError, DatabaseReference databaseReference) {
                                            if (databaseError != null) {
                                                // Handle the error
                                                System.err.println("Error removing question message: " + databaseError.getMessage());
                                            }
                                        }
                                    });

                                     */
                                    break;
                                }
                                case "Why are you late?":{
                                    DatabaseReference parent = dataSnapshot.getRef().getParent().getParent();
                                    String tripId = parent.getKey();
                                    boolean isCause = isCustomerTripCause(tripId, trikeAgent.events);
                                    boolean isCause2 = isCharginTripCause(tripId, trikeAgent.events);
                                    System.out.println("There is a customerTrip before your trip, that does not finish in time. :" + isCause);
                                    System.out.println("There is a chargingTrip before your trip, that does not finish in time. :" + isCause2);
                                    // Push a new message node under the 'messages' node
                                    DatabaseReference newMessageRef = dataSnapshot.getRef().getParent().push();
                                    // Set the message content
                                    newMessageRef.child("message")
                                            .setValueAsync("There is a customerTrip before your trip, that does not finish in time. :" + isCause +
                                                    "\n" + "There is a chargingTrip before your trip, that does not finish in time. :" + isCause2);
                                    // Add other necessary fields, e.g., sender and timestamp if needed
                                    newMessageRef.child("sender").setValueAsync("agent");
                                    break;
                                }
                            }
                        });

                        trikeAgent.listenerHashMap.put(newTrip.getTripID(), childEventListener);


                        //test
                        DatabaseReference tripRef = FirebaseDatabase.getInstance().getReference().child("trips").child(newTrip.tripID).child("messages");

                        // Push a new message node under the 'messages' node
                        DatabaseReference newMessageRef = tripRef.push();

                        // Set the message content synchronously
                        try {
                            ApiFuture<Void> messageFuture = newMessageRef.child("message").setValueAsync("How many trips are scheduled before mine?");
                            messageFuture.get(); // Wait for the setValueAsync operation to complete
                            System.out.println("Message set successfully.");
                        } catch (Exception e) {
                            System.err.println("Error setting message: " + e.getMessage());
                        }
                    }

                    estimateBatteryAfterTIP();

                    if(FIREBASE_ENABLED){
                        FirebaseHandler.assignAgentToTripRequest(newTrip.getTripID(), trikeAgent.agentID);
                    }

                    hasChanged = true;
                    break;
                }
                case NOT_ASSIGNED:
                case DELEGATED:
                case COMMITTED:
                {
                    trikeAgent.FinishedDecisionTaskList.add(currentDecisionTask);
                    iterator.remove();

                    hasChanged = false;
                    break;
                }

                //  manager
                case DELEGATE: {
                    currentDecisionTask.setStatus(DecisionTask.Status.WAITING_NEIGHBOURS);
                    currentDecisionTask.timeStamp = SharedUtils.getSimTime();

                    ArrayList<String> values = new ArrayList<>();
                    values.add(currentDecisionTask.getJobID());

                    // if local trip or a rebalancing trip, do not broadcast cnp
                    if(currentDecisionTask.isLocal || currentDecisionTask.getJob().getID().startsWith("area")){
                        String areaAgentTag = Cells.cellAgentMap.get(trikeAgent.cell);
                        currentDecisionTask.initRequestCount(1);

                        MessageContent messageContent = new MessageContent("trikesInArea", values);
                        Message requestMessage = new Message(trikeAgent.agentID, areaAgentTag, Message.ComAct.REQUEST,
                                SharedUtils.getSimTime(),  messageContent);
                        currentDecisionTask.extra = requestMessage.getId().toString();
                        trikeAgent.requests.add(requestMessage);

                        SharedUtils.sendMessage(requestMessage.getReceiverId(), requestMessage.serialize());
                    }
                    else{
                        //  need to broadcast cnp
                        List<String> areaNeighbourIds = Cells.getNeighbours(currentDecisionTask.cell);

                        currentDecisionTask.initRequestCount(areaNeighbourIds.size());

                        for (String id: areaNeighbourIds) {
                            MessageContent messageContent = new MessageContent("trikesInArea", values);
                            Message requestMessage = new Message(trikeAgent.agentID, id, Message.ComAct.REQUEST,
                                    SharedUtils.getSimTime(),  messageContent);
                            currentDecisionTask.extra = requestMessage.getId().toString();

                            SharedUtils.sendMessage(requestMessage.getReceiverId(), requestMessage.serialize());
                        }
                    }

                    hasChanged = true;
                    long delta = (SharedUtils.getSimTime() - SharedUtils.getTimeStamp(currentDecisionTask.getJob().getVATime())) / 1000;
                    System.out.println("DELEGATE " + currentDecisionTask.getJob().getID() + ": " + currentDecisionTask.getOrigin() + " " +
                            delta);
                    break;
                }
                case WAITING_NEIGHBOURS:{
                    long currentTime = SharedUtils.getSimTime();
                    if (currentTime >= currentDecisionTask.timeStamp + ASK_FOR_TRIKES_WAIT_TIME
                            || currentDecisionTask.responseReady()) {
                        synchronized (trikeAgent.requests){
                            trikeAgent.requests.removeIf(request ->
                                    request.getId().equals(UUID.fromString(currentDecisionTask.extra)));
                        }

                        currentDecisionTask.setStatus(DecisionTask.Status.CFP_READY);
                        hasChanged = true;

                        long delta = (SharedUtils.getSimTime() - SharedUtils.getTimeStamp(currentDecisionTask.getJob().getVATime())) / 1000;
                        System.out.println("WAITING_NEIGHBORS finished " + currentDecisionTask.getJob().getID() + ": " + currentDecisionTask.getOrigin() + " " +
                                delta + " " + currentDecisionTask.numRequests + " " + currentDecisionTask.numResponses.get());

                        break;
                    }

                    hasChanged = false;
                    break;
                }
                case CFP_READY: {
                    //  if not enough trikes for cnp, broadcast it
                    if(currentDecisionTask.getAgentIds().size() < MIN_CNP_TRIKES && currentDecisionTask.isLocal){
                        currentDecisionTask.setStatus(DecisionTask.Status.DELEGATE);
                        currentDecisionTask.isLocal = false;
                        hasChanged = true;
                        break;
                    }

                    if(currentDecisionTask.getAgentIds().isEmpty()){
                        System.out.println("DIDNT RESPOND " + currentDecisionTask.getJobID());
                        long delta = (SharedUtils.getSimTime() - SharedUtils.getTimeStamp(currentDecisionTask.getJob().getVATime())) / 1000;
                        System.out.println("CFP READY NEIGHBORS " + currentDecisionTask.getJob().getID() + ": " + currentDecisionTask.getOrigin() + " " +
                                delta);

                        eventTracker.DecisionTaskCommit(trikeAgent, currentDecisionTask);

                        currentDecisionTask.setStatus(DecisionTask.Status.COMMIT);
                        hasChanged = true;
                        break;
                    }

                    currentDecisionTask.setStatus(DecisionTask.Status.WAITING_PROPOSALS);
                    currentDecisionTask.timeStamp = SharedUtils.getSimTime();

                    Job JobForCFP = currentDecisionTask.getJob();
                    Set<String> agentIds = currentDecisionTask.getAgentIds();

                    currentDecisionTask.initRequestCount(agentIds.size());
                    for (String agentId : agentIds) {
                        testTrikeToTrikeService(agentId, Message.ComAct.CALL_FOR_PROPOSAL, "CallForProposal", JobForCFP.toArrayList());
                    }

                    hasChanged = true;
                    long delta = (SharedUtils.getSimTime() - SharedUtils.getTimeStamp(currentDecisionTask.getJob().getVATime())) / 1000;
                    System.out.println("CFP_READY " + currentDecisionTask.getJob().getID() + ": " + currentDecisionTask.getOrigin() + " " +
                            delta);
                    break;
                }
                case WAITING_PROPOSALS: {
                    long currentTime = SharedUtils.getSimTime();
                    if (currentTime >= currentDecisionTask.timeStamp + PROPOSALS_WAIT_TIME
                            || currentDecisionTask.numRequests == currentDecisionTask.getUTScoreList().size() - 1) {
                        currentDecisionTask.setStatus(DecisionTask.Status.DECISION_READY);
                        long delta = (SharedUtils.getSimTime() - SharedUtils.getTimeStamp(currentDecisionTask.getJob().getVATime())) / 1000;
                        System.out.println("WAITING PROPOSALS " + currentDecisionTask.getJob().getID() + ": " + currentDecisionTask.getOrigin() + " " +
                                delta + " " + currentDecisionTask.numRequests + " " + currentDecisionTask.numResponses.get());


                        hasChanged = true;
                        break;
                    }

                    hasChanged = false;
                    break;
                }
                case DECISION_READY: {
                    /**
                     *  send agree/cancel > "waitingForConfirmations"
                     */
                    currentDecisionTask.tagBestScore(trikeAgent.agentID);
                    synchronized (currentDecisionTask.getUTScoreList()) {
                        for (int i = 0; i < currentDecisionTask.getUTScoreList().size(); i++) {
                            String bidderID = currentDecisionTask.getUTScoreList().get(i).getBidderID();
                            String tag = currentDecisionTask.getUTScoreList().get(i).getTag();


                            switch (tag) {
                                case "AcceptProposal": {
                                    currentDecisionTask.setStatus(DecisionTask.Status.WAITING_CONFIRM);
                                    currentDecisionTask.timeStamp = SharedUtils.getSimTime();

                                    ArrayList<String> values = new ArrayList<>();
                                    values.add(currentDecisionTask.getJobID());

                                    MessageContent messageContent = new MessageContent(tag, values);
                                    Message acceptMessage = new Message(trikeAgent.agentID, bidderID,
                                            Message.ComAct.ACCEPT_PROPOSAL, SharedUtils.getSimTime(), messageContent);

                                    trikeAgent.requests.add(acceptMessage);
                                    currentDecisionTask.extra = acceptMessage.getId().toString();
                                    //IAreaTrikeService service = messageToService(trikeAgent.agent, acceptMessage);
                                    SharedUtils.sendMessage(acceptMessage.getReceiverId(), acceptMessage.serialize());
                                    break;
                                }
                                case "RejectProposal": {
                                    ArrayList<String> values = new ArrayList<>();
                                    values.add(currentDecisionTask.getJobID());
                                    testTrikeToTrikeService(bidderID, Message.ComAct.REJECT_PROPOSAL, tag, values);
                                    break;
                                }
                                case "AcceptSelf": {

                                    eventTracker.DecisionTaskCommit(trikeAgent, currentDecisionTask);

                                    currentDecisionTask.setStatus(DecisionTask.Status.COMMIT);
                                    String timeStampBooked = new SimpleDateFormat("HH.mm.ss.ms")
                                            .format(new java.util.Date());
                                    System.out.println("FINISHED Negotiation - JobID: " +
                                            currentDecisionTask.getJobID() + " TimeStamp: " + timeStampBooked);
                                    break;
                                }
                            }
                        }
                    }

                    long delta = (SharedUtils.getSimTime() - SharedUtils.getTimeStamp(currentDecisionTask.getJob().getVATime())) / 1000;
                    System.out.println("DECISION_READY " + currentDecisionTask.getJob().getID() + ": " + currentDecisionTask.getOrigin() + " " +
                            delta);
                    hasChanged = true;
                    break;
                }
                case WAITING_CONFIRM: {
                    long currentTime = SharedUtils.getSimTime();
                    if (currentTime >= currentDecisionTask.timeStamp + CONFIRM_WAIT_TIME) {
                        synchronized (trikeAgent.requests){
                            trikeAgent.requests.removeIf(request -> request.getId()
                                    .equals(UUID.fromString(currentDecisionTask.extra)));
                        }
                        //currentDecisionTask.setStatus(DecisionTask.Status.COMMIT);
                        currentDecisionTask.setStatus(DecisionTask.Status.DELEGATED);
                        long delta = (SharedUtils.getSimTime() - SharedUtils.getTimeStamp(currentDecisionTask.getJob().getVATime())) / 1000;
                        System.out.println("WAITING CONFIRM TIMEOUT " + currentDecisionTask.getJob().getID() + ": " + currentDecisionTask.getOrigin() + " " +
                                delta);
                        hasChanged = true;
                        break;
                    }

                    hasChanged = false;
                    break;
                }

                //  worker
                case PROPOSED: {
                    currentDecisionTask.setStatus(DecisionTask.Status.WAITING_MANAGER);
                    currentDecisionTask.timeStamp = SharedUtils.getSimTime();

                    /**
                     *  send bid > "waitingForManager"
                     */
                    Double ownScore = calculateUtility(currentDecisionTask);
                    //todo: eigene utillity speichern
                    // send bid
                    // ursprung des proposed job bestimmen
                    ArrayList<String> values = new ArrayList<>();

                    values.add(currentDecisionTask.getJobID());
                    values.add("#");
                    values.add(String.valueOf(ownScore));

                    //zb. values = jobid # score
                    testTrikeToTrikeService(currentDecisionTask.getOrigin(), Message.ComAct.PROPOSE, "Propose", values);


                    hasChanged = true;
                    long delta = (SharedUtils.getSimTime() - SharedUtils.getTimeStamp(currentDecisionTask.getJob().getVATime())) / 1000;
                    System.out.println("PROPOSED " + currentDecisionTask.getJob().getID() + ": " + currentDecisionTask.getOrigin() + " " +
                            delta);
                    break;
                }
                case WAITING_MANAGER: {
                    //  timeout
                    long currentTime = SharedUtils.getSimTime();
                    if (currentTime >= currentDecisionTask.timeStamp + MANAGER_WAIT_TIME) {
                        currentDecisionTask.setStatus(DecisionTask.Status.NOT_ASSIGNED);
                        hasChanged = true;
                        long delta = (SharedUtils.getSimTime() - SharedUtils.getTimeStamp(currentDecisionTask.getJob().getVATime())) / 1000;
                        System.out.println("WAITING MANAGER TIMEOUT " + currentDecisionTask.getJob().getID() + ": " + currentDecisionTask.getOrigin() + " " +
                                delta);
                        break;
                    }

                    hasChanged = false;
                    break;
                }
                case CONFIRM_READY: {
                    long currentTime = SharedUtils.getSimTime();
                    //if(currentTime >= currentDecisionTask.timeStamp + CONFIRM_WAIT_TIME * 3L){
                    //    currentDecisionTask.setStatus(DecisionTask.Status.NOT_ASSIGNED);
                    //    System.out.println("CANCELLED");
                    //    long delta = (SharedUtils.getSimTime() - SharedUtils.getTimeStamp(currentDecisionTask.getJob().getVATime())) / 1000;
                    //    System.out.println("CANCELLED " + currentDecisionTask.getJob().getID() + ": " + currentDecisionTask.getOrigin() + " " +
                    //            delta);
                    //    hasChanged = true;
                    //    break;
                    //}

                    eventTracker.DecisionTaskCommit(trikeAgent, currentDecisionTask);

                    currentDecisionTask.setStatus(DecisionTask.Status.COMMIT);
                    String timeStampBooked = new SimpleDateFormat("HH.mm.ss.ms").format(new java.util.Date());
                    System.out.println("FINISHED Negotiation - JobID: " + currentDecisionTask.getJobID() +
                            " TimeStamp: " + timeStampBooked);

                    MessageContent messageContent = new MessageContent("confirmAccept");
                    messageContent.values.add(currentDecisionTask.getJobID());

                    Message message = new Message(trikeAgent.agentID, currentDecisionTask.getOrigin(), Message.ComAct.ACK,
                            SharedUtils.getSimTime(), messageContent);
                    message.setId(UUID.fromString(currentDecisionTask.extra));
                    //IAreaTrikeService service = messageToService(trikeAgent.agent, message);
                    SharedUtils.sendMessage(message.getReceiverId(), message.serialize());

                    hasChanged = true;
                    break;
                }

                default:
                    hasChanged = false;
                    break;
            }
        }while(hasChanged && ++iterations <= 10);
    }

    /** Utillity Function
     * should be switchable between a regular and a learning attempt
     * todo: assumption bookingtime = vatime
     * todo: fortschritt von trikeAgent.tripIDList berücksichtigen!
     * @return
     */
    public  Double calculateUtility(DecisionTask newTask){
        Double utillityScore = 0.0;

        synchronized (trikeAgent.tripList){
            if (trikeAgent.chargingTripAvailable.equals("0")) {
                Double a = 0.8;
                Double b = 0.15;
                Double c = 0.05;

                Double uPunctuality = null;
                Double uBattery = null;
                Double uDistance = null;

                //###########################################################
                // punctuallity
                // arrival delay to arrive at the start position when started from the trikeAgent.agentLocation
                //todo: number of comitted trips TIP über alle berechnen erwartete ankunft bei aktuellem bestimmen, dann delay bewerten ohne ladefahrten
                Double vaTimeFirstTrip = null;
                //when there is no Trip before calculate the delay when started at the Agent Location
                if (trikeAgent.currentTrip.size() == 0 && trikeAgent.tripList.size() == 0) {
                    //trikeAgent.agentLocation
                    Double distanceToStart = getDistanceBetween(trikeAgent.agentLocation, newTask.getStartPositionFromJob());
                    //Double vATimeNewTask = timeInSeconds(newTask.getVATimeFromJob());
                    Double timeToNewTask = ((distanceToStart/1000) / DRIVING_SPEED)*60*60; //in this case equals the delay as vatiem is bookingtime
                    // transforms the delay in seconds into as score beween 0 and 100 based of the max allowed delay of 900s
                    if (timeToNewTask<THETA){
                        uPunctuality = 100.0;
                    }
                    else if (THETA<= timeToNewTask && timeToNewTask<=2*THETA){
                        uPunctuality = 100.0 - ((100.0 * timeToNewTask - THETA)/THETA);
                    }
                    else{
                        uPunctuality = 0.0;
                    }

                    //uPunctuality = Math.min(100.0, (100.0 - (((Math.min(THETA, timeToNewTask) - 0.0) / (THETA - 0.0)) * 100.0)));
                }
                else {
                    Double totalDistance_TIP = 0.0;
                    //todo: get va time of first job here or in an else case
                    if (trikeAgent.currentTrip.size() == 1) { //distances driven from the agent location to the start of the current trip and to its end
                        totalDistance_TIP += getDistanceBetween(trikeAgent.agentLocation, trikeAgent.currentTrip.get(0).getStartPosition());
                        if (trikeAgent.currentTrip.get(0).getTripType().equals("CustomerTrip")) { //only drive to the end when it is a customerTrip
                            vaTimeFirstTrip = timeInSeconds(trikeAgent.currentTrip.get(0).getVATime());
                            totalDistance_TIP += getDistanceBetween(trikeAgent.currentTrip.get(0).getStartPosition(), trikeAgent.currentTrip.get(0).getEndPosition());
                        }
                    }
                    //  distance driven at trikeAgent.tripList
                    if (trikeAgent.tripList.size() > 0) {
                        if (trikeAgent.currentTrip.size() > 0) { //journey to the first entry in the trikeAgent.tripList from a trikeAgent.currentTrip
                            if (trikeAgent.currentTrip.get(0).getTripType().equals("CustomerTrip")) {
                                totalDistance_TIP += getDistanceBetween(trikeAgent.currentTrip.get(0).getEndPosition(), trikeAgent.tripList.get(0).getStartPosition());
                            } else { // trips with only a start position
                                totalDistance_TIP += getDistanceBetween(trikeAgent.currentTrip.get(0).getStartPosition(), trikeAgent.tripList.get(0).getStartPosition());
                            }
                        } else { //journey to the first entry in the trikeAgent.tripList from the trikeAgent.agentLocation
                            vaTimeFirstTrip = timeInSeconds(trikeAgent.tripList.get(0).getVATime()); //fist VATime when there was no trikeAgent.currentTrip
                            totalDistance_TIP += getDistanceBetween(trikeAgent.agentLocation, trikeAgent.tripList.get(0).getStartPosition());
                        }
                        // distance driven at trikeAgent.tripList.get(0)
                        if (trikeAgent.tripList.get(0).getTripType().equals("CustomerTrip")) {
                            totalDistance_TIP += getDistanceBetween(trikeAgent.tripList.get(0).getStartPosition(), trikeAgent.tripList.get(0).getEndPosition());
                        }
                    } else {
                        // do nothing as all other Trips with only a startPosition will not contain any other movements;
                    }

                    // interates through all other Trips inside trikeAgent.tripList
                    if (trikeAgent.tripList.size() > 1){ //added to avoid crashes
                        for (int i = 1; i < trikeAgent.tripList.size(); i++) {
                            if (trikeAgent.tripList.get(i - 1).getTripType().equals("CustomerTrip")) {
                                totalDistance_TIP += getDistanceBetween(trikeAgent.tripList.get(i - 1).getEndPosition(), trikeAgent.tripList.get(i).getStartPosition()); //trikeAgent.tripList or trikeAgent.currentTrip
                            } else { // Trips with only a startPosition
                                totalDistance_TIP += getDistanceBetween(trikeAgent.tripList.get(i - 1).getStartPosition(), trikeAgent.tripList.get(i).getStartPosition()); //corrected! was to EndPosition before!
                            }
                            if (trikeAgent.tripList.get(i).getTripType().equals("CustomerTrip")) { //trikeAgent.tripList or trikeAgent.currentTrip
                                totalDistance_TIP += getDistanceBetween(trikeAgent.tripList.get(i).getStartPosition(), trikeAgent.tripList.get(i).getEndPosition());
                            }
                        }
                    }
                    //todo: drives to the start of the job that has to be evaluated
                    if (getLastTripInPipeline().getTripType().equals("CustomerTrip")) {
                        totalDistance_TIP += getDistanceBetween(getLastTripInPipeline().getEndPosition(), newTask.getStartPositionFromJob());
                    }
                    else {
                        totalDistance_TIP += getDistanceBetween(getLastTripInPipeline().getStartPosition(), newTask.getStartPositionFromJob());
                    }


                    Double vATimeNewTask = timeInSeconds(newTask.getVATimeFromJob());
                    Double timeToNewTask = ((totalDistance_TIP/1000) / DRIVING_SPEED)*60*60;
                    Double arrivalAtNewtask = vaTimeFirstTrip + timeToNewTask;

                    Double delayArrvialNewTask = Math.max((arrivalAtNewtask - vATimeNewTask), timeToNewTask);
                    System.out.println("vATimeNewTask: " + vATimeNewTask );
                    System.out.println("timeToNewTask: " + timeToNewTask );
                    System.out.println("arrivalAtNewtask: " + arrivalAtNewtask );
                    System.out.println("delayArrvialNewTask: " + delayArrvialNewTask );

                    if (delayArrvialNewTask<THETA){
                        uPunctuality = 100.0;
                    }
                    else if (THETA<= delayArrvialNewTask && delayArrvialNewTask <=2*THETA){
                        uPunctuality = 100.0 - ((100.0 * delayArrvialNewTask - THETA)/THETA);
                    }
                    else{
                        uPunctuality = 0.0;
                    }

                    //uPunctuality = Math.min(100.0, (100.0 - (((Math.min(THETA, delayArrvialNewTask) - 0.0) / (THETA - 0.0)) * 100.0)));



                }
                //when there a trips iterate through all, starting at the va time of the first trip estimate your delay when arriving at the start location of
                // the Job that has to be evaluated


                //###########################################################
                // Battery
                //todo: battery from Ömer needed
                // differ between trips with and without customer???
                Double currentBatteryLevel = trikeAgent.trikeBattery.getMyChargestate(); //todo: use real battery
                Double estBatteryLevelAfter_TIP = trikeAgent.trikeBattery.getMyChargestate();
                Double estDistance = 0.0;
                Double estEnergyConsumption = 0.0;
                Double estEnergyConsumption_TIP = 0.0;
                Double totalDistance_TIP = 0.0;
                Double negativeInfinity = Double.NEGATIVE_INFINITY;
                Double bFactor = null;
                //todo ennergieverbrauch für zu evuluierenden job bestimmen

                //calculation of the estimatedEnergyConsumtion (of formertrips)


                if (trikeAgent.currentTrip.size() == 1) { //battery relavant distance driven at trikeAgent.currentTrip
                    //todo: fortschritt von trikeAgent.currentTrip berücksichtigen
                    totalDistance_TIP += getDistanceBetween(trikeAgent.agentLocation, trikeAgent.currentTrip.get(0).getStartPosition());
                    if (trikeAgent.currentTrip.get(0).getTripType().equals("CustomerTrip")) { //only drive to the end when it is a customerTrip
                        totalDistance_TIP += getDistanceBetween(trikeAgent.currentTrip.get(0).getStartPosition(), trikeAgent.currentTrip.get(0).getEndPosition());
                    }
                    if (trikeAgent.currentTrip.get(0).getTripType().equals("ChargingTrip")) {
                        totalDistance_TIP = 0.0; //reset the distance until now because only the distance after a chargingTrip influences the battery
                    }
                }
                // battery relavant distance driven at trikeAgent.tripList
                if (trikeAgent.tripList.size() > 0) {
                    if (trikeAgent.currentTrip.size() > 0) { //journey to the first entry in the trikeAgent.tripList from a trikeAgent.currentTrip
                        if (trikeAgent.currentTrip.get(0).getTripType().equals("CustomerTrip")) {
                            totalDistance_TIP += getDistanceBetween(trikeAgent.currentTrip.get(0).getEndPosition(), trikeAgent.tripList.get(0).getStartPosition());
                        } else { // trips with only a start position
                            totalDistance_TIP += getDistanceBetween(trikeAgent.currentTrip.get(0).getStartPosition(), trikeAgent.tripList.get(0).getStartPosition());
                        }
                    } else { //journey to the first entry in the trikeAgent.tripList from the trikeAgent.agentLocation
                        totalDistance_TIP += getDistanceBetween(trikeAgent.agentLocation, trikeAgent.tripList.get(0).getStartPosition());
                    }
                    // distance driven at trikeAgent.tripList.get(0)
                    if (trikeAgent.tripList.get(0).getTripType().equals("CustomerTrip")) {
                        totalDistance_TIP += getDistanceBetween(trikeAgent.tripList.get(0).getStartPosition(), trikeAgent.tripList.get(0).getEndPosition());
                    }
                    if (trikeAgent.tripList.get(0).getTripType().equals("ChargingTrip")) {
                        totalDistance_TIP = 0.0;
                    } else {
                        // do nothing as all other Trips with only a startPosition will not contain any other movements;
                    }


                    //todo: fahrt zum nächjsten start fehlt +-1 bei i???
                    // interates through all other Trips inside trikeAgent.tripList
                    if (trikeAgent.tripList.size() > 1){ //added to avoid crashes
                        for (int i = 1; i < trikeAgent.tripList.size(); i++) {
                            if (trikeAgent.tripList.get(i - 1).getTripType().equals("CustomerTrip")) {
                                totalDistance_TIP += getDistanceBetween(trikeAgent.tripList.get(i - 1).getEndPosition(), trikeAgent.tripList.get(i).getStartPosition()); //trikeAgent.tripList or trikeAgent.currentTrip
                            } else { // Trips with only a startPosition
                                totalDistance_TIP += getDistanceBetween(trikeAgent.tripList.get(i - 1).getStartPosition(), trikeAgent.tripList.get(i).getStartPosition()); //corrected! was to EndPosition before!
                            }
                            if (trikeAgent.tripList.get(i).getTripType().equals("CustomerTrip")) { //trikeAgent.tripList or trikeAgent.currentTrip
                                totalDistance_TIP += getDistanceBetween(trikeAgent.tripList.get(i).getStartPosition(), trikeAgent.tripList.get(i).getEndPosition());
                            }
                        }
                    }
                }
                //todo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! RICHTIGE WERTE ZUGREIFEN
                estEnergyConsumption_TIP = trikeAgent.trikeBattery.SimulateDischarge(totalDistance_TIP * DISTANCE_FACTOR);//*2 because it would be critical to underestimate the distance
                estBatteryLevelAfter_TIP = currentBatteryLevel - estEnergyConsumption_TIP;

                //calculate teh estimated energy consumption of the new job


                //Distance from the agent location
                if (trikeAgent.currentTrip.size() == 0 && trikeAgent.tripList.size() == 0){
                    estDistance += getDistanceBetween(trikeAgent.agentLocation, newTask.getStartPositionFromJob());
                }
                //Distance from the Last Trip in Pipe
                else{
                    if (getLastTripInPipeline().getTripType().equals("CustomerTrip")){
                        estDistance += getDistanceBetween(getLastTripInPipeline().getEndPosition(), newTask.getStartPositionFromJob());
                    }
                    else{
                        estDistance += getDistanceBetween(getLastTripInPipeline().getStartPosition(), newTask.getStartPositionFromJob());
                    }
                }
                estDistance += getDistanceBetween(newTask.getStartPositionFromJob(), newTask.getEndPositionFromJob());

                estEnergyConsumption = trikeAgent.trikeBattery.SimulateDischarge(estDistance * DISTANCE_FACTOR);

                Double estBatterylevelTotal = estBatteryLevelAfter_TIP - estEnergyConsumption;


                //###########################################################
                // calculation of uBattery
                if (estBatterylevelTotal < 0.0) { //todo: estEnergyConsumption FEHLT!
                    uBattery = negativeInfinity;
                } else {
                    if (estBatterylevelTotal > 0.8) {
                        bFactor = 1.0;
                    } else if (estBatterylevelTotal >= 0.3) {
                        bFactor = 0.75;
                    } else if (estBatterylevelTotal < 0.3) {
                        bFactor = 0.1;
                    }
                    // ???? batteryLevelAfterTrips or 100?
                    uBattery = (bFactor * estBatterylevelTotal) * 100;

                }
                //###########################################################
                //Distance
                Double dmax = 20000.0;
                Double distanceToStart;

                if (trikeAgent.tripList.size() == 0 && trikeAgent.currentTrip.size() == 0) {
                    distanceToStart = getDistanceBetween(trikeAgent.agentLocation, newTask.getStartPositionFromJob());
                } else {
                    if (getLastTripInPipeline().getTripType().equals("CustomerTrip")) {
                        distanceToStart = getDistanceBetween(getLastTripInPipeline().getEndPosition(), newTask.getStartPositionFromJob());
                    } else {
                        distanceToStart = getDistanceBetween(getLastTripInPipeline().getStartPosition(), newTask.getStartPositionFromJob());
                    }
                }
                uDistance = Math.max(0, (100-distanceToStart / dmax));
                //uDistance = Math.max(0, Math.min(100, (100.0 - ((distanceToStart / dmax) * 100.0))));


                //###########################################################


                // calculate the total score

                utillityScore = Math.max(0.0, (a * uPunctuality + b * uBattery + c * uDistance));
            }
            //System.out.println("trikeAgent.agentID: " + trikeAgent.agentID + "utillity: " + utillityScore);
        }

        return utillityScore;
    }


    //  example of trike to trike communic ation
    public void testTrikeToTrikeService(String receiverID, Message.ComAct comAct, String action, ArrayList<String> values){
        //message creation
        MessageContent messageContent = new MessageContent(action, values);
        Message testMessage = new Message(trikeAgent.agentID,""+receiverID, comAct, SharedUtils.getSimTime(),  messageContent);

        //calls trikeMessage methods of TrikeAgentService class
        SharedUtils.sendMessage(testMessage.getReceiverId(), testMessage.serialize());
    }
    public Double timeInSeconds(LocalDateTime time) {
        // Option 1: If the difference is greater than 300 seconds (5 minutes OR 300 seconds or 300000 millisec), then customer missed, -oemer

        double vaTimeSec = time.atZone(ZoneId.systemDefault()).toEpochSecond();
        return vaTimeSec;
    }

    public void changeArea(String originArea, String newArea) {
        //deregister from old
        MessageContent messageContent = new MessageContent("deregister", null);
        Message deregisterMessage = new Message( trikeAgent.agentID, originArea, Message.ComAct.INFORM, SharedUtils.getSimTime(), messageContent);

        //query assigning
        //IAreaTrikeService service = messageToService(trikeAgent.agent, deregisterMessage);

        //calls updateAreaAgent of AreaAgentService class
        //IAreaTrikeService finalService = service;
        SharedUtils.sendMessage(deregisterMessage.getReceiverId(), deregisterMessage.serialize());

        //register to new
        messageContent = new MessageContent("register", null);
        Message registerMessage = new Message( trikeAgent.agentID, newArea, Message.ComAct.INFORM, SharedUtils.getSimTime(), messageContent);

        //query assigning
        //service = messageToService(trikeAgent.agent, registerMessage);

        //calls updateAreaAgent of AreaAgentService class
        //IAreaTrikeService finalService1 = service;
        SharedUtils.sendMessage(registerMessage.getReceiverId(), registerMessage.serialize());
    }
    public void sendAreaAgentUpdate(String action){
        //location
        ArrayList<String> values = new ArrayList<>();
        values.add(Double.toString(trikeAgent.agentLocation.getX()));
        values.add(Double.toString(trikeAgent.agentLocation.getY()));

        //  init register of trikes
        if (action.equals("register")){
            trikeAgent.cell = Cells.findKey(trikeAgent.agentLocation);
        }

        //  get target AreaAgent tag based on the cell address
        String areaAgentTag = Cells.cellAgentMap.get(trikeAgent.cell);

        //  update/register message
        MessageContent messageContent = new MessageContent(action, values);
        Message testMessage = new Message( trikeAgent.agentID, areaAgentTag, Message.ComAct.INFORM, JadexModel.simulationtime,  messageContent);

        if(action.equals("register")){
            //query assigning
            IAreaTrikeService service = messageToService(trikeAgent.agent, testMessage);
            service.sendMessage(testMessage.serialize());
            System.out.println(trikeAgent.agentID + " registered to " + areaAgentTag);
        }else{
            SharedUtils.sendMessage(testMessage.getReceiverId(), testMessage.serialize());
        }
    }

    // After a succefull action in MATSIm: Updates the progreess of the current Trip and the Agent location
    //todo: better get the location from MATSim
    public void updateBeliefAfterAction(double metersDriven) {
        Trip CurrentTripUpdate = trikeAgent.currentTrip.get(0);
        //double metersDriven = 100.0;
        //Transport ohne Kunde
        String arrivedAtLocation = "true";

        if (CurrentTripUpdate.getProgress().equals("DriveToStart")) {
            updateCurrentTripProgress("AtStartLocation");
            trikeAgent.agentLocation = CurrentTripUpdate.getStartPosition();
            String batteryBefore = Double.toString(trikeAgent.trikeBattery.getMyChargestate()); //todo: vorher schieben
            trikeAgent.trikeBattery.discharge(metersDriven, 0);
            String batteryAfter = Double.toString(trikeAgent.trikeBattery.getMyChargestate());
            //String arrivedAtLocation = "true";
            if (trikeAgent.trikeBattery.getMyChargestate() < 0.0){
                arrivedAtLocation = "false";
                updateCurrentTripProgress("Failed");

            }
            String distance = Double.toString(metersDriven);
            prepareLog(CurrentTripUpdate, batteryBefore, batteryAfter, arrivedAtLocation, distance);

            if (arrivedAtLocation.equals("false")){
                trikeAgent.currentTrip.remove(0);
                terminateTripList();
            }
        }


        //Transport mit Kunde
        if (CurrentTripUpdate.getProgress().equals("DriveToEnd")){
            updateCurrentTripProgress("AtEndLocation");
            trikeAgent.agentLocation = CurrentTripUpdate.getEndPosition();
            String batteryBefore = Double.toString(trikeAgent.trikeBattery.getMyChargestate()); //todo: vorher schieben
            trikeAgent.trikeBattery.discharge(metersDriven, 1);
            String batteryAfter = Double.toString(trikeAgent.trikeBattery.getMyChargestate());
            if(FIREBASE_ENABLED){
                FirebaseHandler.removeChildEventListener("trips/"+trikeAgent.currentTrip.get(0).getTripID(), trikeAgent.listenerHashMap.get(trikeAgent.currentTrip.get(0).getTripID()));
            }
            //String arrivedAtLocation = "true";
            if (trikeAgent.trikeBattery.getMyChargestate() < 0.0){
                arrivedAtLocation = "false";
                updateCurrentTripProgress("Failed");
            }
            String distance = Double.toString(metersDriven);
            prepareLog(CurrentTripUpdate, batteryBefore, batteryAfter, arrivedAtLocation, distance);

            if (arrivedAtLocation.equals("false")){
                trikeAgent.currentTrip.remove(0);
                terminateTripList();
            }
        }

        if(FIREBASE_ENABLED){
            // Update Firebase with the current progress
            FirebaseHandler.sendTripProgress(CurrentTripUpdate.getTripID(), CurrentTripUpdate.getProgress());
            /**
             * TODO: @Mariam update firebase after every MATSim action: location of the agent
             */
        }


        //todo: action und perceive trennen! aktuell beides in beiden listen! löschen so nicht konsistent!
        //TODO: @Mahkam send Updates to AreaAgent
        //currentTripStatus();
    }

    public void terminateTripList(){
        synchronized (trikeAgent.tripList){
            synchronized (trikeAgent.currentTrip){
                if (trikeAgent.currentTrip.size() > 1){
                    prepareLog(trikeAgent.currentTrip.get(0), "0.0", "0.0", "false", "0.0");
                    trikeAgent.currentTrip.get(0).setProgress("Failed");
                    trikeAgent.currentTrip.remove(0);
                }
            }
            if (trikeAgent.tripList.size() > 0){
                while (trikeAgent.tripList.size() > 0) {
                    prepareLog(trikeAgent.tripList.get(0), "0.0", "0.0", "false", "0.0");
                    trikeAgent.tripList.get(0).setProgress("Failed");
                    trikeAgent.tripList.remove(0);
                }
            }
        }

        trikeAgent.trikeBattery.loadBattery();
        trikeAgent.chargingTripAvailable = "0";

        System.out.println("AgentID: " + trikeAgent.agentID + "ALL TRIPS TERMINATED");
    }
    public void prepareLog(Trip trip, String batteryBefore, String batteryAfter, String arrivedAtLocation, String distance){
        String tripID = trip.getTripID();
        String tripType = trip.getTripType();
        String driveOperationNumber = "1";
        String origin = "";
        if (trip.getProgress().equals("AtEndLocation")){
            driveOperationNumber = "2";
        }
        String arrivalTime = "0.0"; //when it was not a CustomerTrip
        if (trip.getTripType().equals("CustomerTrip")){
            arrivalTime = Double.toString(ArrivalTime(trip.getVATime()));
            origin = "trike:" + trip.getDecisionTask().getOrigin();
        }
        csvLogger.addLog(trikeAgent.agentID, CNP_ACTIVE, THETA, ALLOW_CUSTOMER_MISS, CHARGING_THRESHOLD, commitThreshold, DISTANCE_FACTOR, "trike:" + trikeAgent.agentID, tripID, driveOperationNumber, tripType, batteryBefore, batteryAfter, arrivedAtLocation, distance, arrivalTime, origin);
    }

    public Double ArrivalTime(LocalDateTime vATime){
        long offset = (vATime
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

        long vaTimeMilli = vATime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        double curr = (JadexModel.simulationtime) * 1000;
        double diff = (curr - (vaTimeMilli - offset))/1000 ; //in seconds
        //Double arrivalTime;
        return diff;
    };

    public void currentTripStatus() {
        if (trikeAgent.currentTrip.size() > 0){
            System.out.println("\n currentTripStatus:");
            System.out.println("AgentID: " + trikeAgent.agentID + " currentTripID: " + trikeAgent.currentTrip.get(0).getTripID());
            System.out.println("AgentID: " + trikeAgent.agentID + " currentTripType: " + trikeAgent.currentTrip.get(0).getTripType());
            System.out.println("AgentID: " + trikeAgent.agentID + " currentVaTime: " + trikeAgent.currentTrip.get(0).getVATime());
            System.out.println("AgentID: " + trikeAgent.agentID + " currentStartPosition: " + trikeAgent.currentTrip.get(0).getStartPosition());
            System.out.println("AgentID: " + trikeAgent.agentID + " currentEndPosition: " +trikeAgent.currentTrip.get(0).getEndPosition());
            System.out.println("AgentID: " + trikeAgent.agentID + " currentProgress: " + trikeAgent.currentTrip.get(0).getProgress());
            System.out.println("AgentID: " + trikeAgent.agentID + " endTime: " + trikeAgent.currentTrip.get(0).getEndTime());
        }

    }
    /** Updates the progress of the CurrentTrip
     *
     * @param newProgress
     */
    public void updateCurrentTripProgress(String newProgress) {
        Trip CurrentTripUpdate = trikeAgent.currentTrip.get(0);
        CurrentTripUpdate.setProgress(newProgress);
        trikeAgent.currentTrip.set(0, CurrentTripUpdate);
        //currentTripStatus();
    }

    public boolean customerMiss(Trip trip) {
        long offset = (trip.getVATime()
                .withHour(0)
                .withMinute(0)
                .withSecond(0)
                .withNano(0)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());



        // Option 1: If the difference is greater than 300 seconds (5 minutes OR 300 seconds or 300000 millisec), then customer missed, -oemer
        boolean isMissed = false;
        long vaTimeMilli = trip.getVATime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        //Double vaTimeSec = timeInSeconds(currentTrip.get(0).getVATime());
        double curr = (JadexModel.simulationtime) * 1000;
        double diff = curr - (vaTimeMilli - offset) ;
        if (diff > (THETA*1000) && ALLOW_CUSTOMER_MISS){
            return isMissed = true;
        }
        return isMissed;
    }

    public void sendDriveTotoAdc()
    {
        Object[] Endparams = new Object[7];
        // needs to get seperate parameter for different types of trip
        if (trikeAgent.currentTrip.get(0).getProgress().equals("NotStarted"))
        {
            Endparams[0] = Constants.DRIVETO;
            Endparams[1] = trikeAgent.currentTrip.get(0).getStartPosition().getCoordinates();

        }
        if (trikeAgent.currentTrip.get(0).getProgress().equals("AtStartLocation"))
        {
            Endparams[0] = Constants.DRIVETO;
            Endparams[1] = trikeAgent.currentTrip.get(0).getEndPosition().getCoordinates();
        }
        Endparams[2] = JadexModel.simulationtime;
        Endparams[3] = Constants.EvacRoutingMode.carFreespeed;
        Endparams[4] = "EvacPlace";
        Endparams[5] = trikeAgent.currentTrip.get(0).getTripID();
        //added oemer
        Endparams[6] = trikeAgent.sumLinkLength;
        trikeAgent.SimActuator.getEnvironmentActionInterface().packageAction(trikeAgent.agentID, "drive_to", Endparams, null);
    }

    public void SendPerceivetoAdc() // needs to send in the begining to subscribe to events in MATSIM
    {
        Object[] params = new Object[8];
        params[0] = "blocked";
        params[1] = "congestion";
        params[2] = "arrived"; // five secs from now;
        params[3] = "departed";
        params[4] = "activity_started";
        params[5] = "activity_ended"; // add replan activity to mark location/time of replanning
        params[6] = "stuck";
        params[7] = "sum_link_length"; //added -oemer

        trikeAgent.SimActuator.getEnvironmentActionInterface().packageAction(trikeAgent.agentID, "perceive", params, "");
    }

    public double getDrivingDistanceTo(Location location) { // EUclician Distanz
        double dist =
                0;
        try {
            dist = (double)trikeAgent.SimActuator.getQueryPerceptInterface().queryPercept(
                    String.valueOf(trikeAgent.agentID),
                    Constants.REQUEST_DRIVING_DISTANCE_TO,
                    location.getCoordinates());
        } catch (AgentNotFoundException e) {
            throw new RuntimeException(e);
        }
        return dist;
    }

    public  Location[] getCurrentLocation() throws AgentNotFoundException {
        Object obj = trikeAgent.SimActuator.getQueryPerceptInterface().queryPercept(String.valueOf(trikeAgent.agentID), Constants.REQUEST_LOCATION, null);
        Location[] location = (Location[]) obj;
        return location;
    }

    public double getDistanceBetween(Location location1, Location location2){
        double[][] args = new double[2][2];
        args[0][0] = location1.getX();
        args[0][1] = location1.getY();
        args[1][0] = location2.getX();
        args[1][1] = location2.getY();

        try {
            return (double)trikeAgent.SimActuator.getQueryPerceptInterface().queryPercept(
                    String.valueOf(trikeAgent.agentID),
                    "request_distance_of_locations",
                    args);
        } catch (AgentNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    ///////////////////////////////////////////////////////
    //  updates locatedagentlist of the area agent


    public String getRandomSimInputBroker() // choose random SimInputBroker to register in the begining
    {
        List<String> SimInputBrokerList = SimIDMapper.NumberSimInputAssignedID;
        Random rand = new Random();
        String randomSimInputBroker = SimInputBrokerList.get(rand.nextInt(SimInputBrokerList.size()));
        return randomSimInputBroker;
    }


    public void newCurrentTrip(){
        synchronized (trikeAgent.currentTrip){
            synchronized (trikeAgent.tripList){
                if(trikeAgent.currentTrip.isEmpty() && !trikeAgent.tripList.isEmpty()){
                    trikeAgent.currentTrip.add(trikeAgent.tripList.remove(0));
                    eventTracker.TripList_BeliefUpdated(trikeAgent);
                }
            }
        }
    }

    /**
     * for the sny of the cycle
     */
    public void updateAtInputBroker(){
        ServiceQuery<INotifyService2> query = new ServiceQuery<>(INotifyService2.class);
        query.setScope(ServiceScope.PLATFORM); // local platform, for remote use GLOBAL
        query.setServiceTags("" + trikeAgent.currentSimInputBroker);
        Collection<INotifyService2> service = trikeAgent.agent.getLocalServices(query);
        for (Iterator<INotifyService2> iteration = service.iterator(); iteration.hasNext(); ) {
            INotifyService2 cs = iteration.next();
            cs.removeTrikeAgentfromActiveList(trikeAgent.agentID);
            System.out.println(" Newly active Agent " + trikeAgent.agentID + "notifies" + trikeAgent.currentSimInputBroker + " that it finished deliberating");
        }
    }

    public static boolean isCustomerTripCause(String questionerTripID, List<Event<?>> trikeEvents) {
        List<Event<?>> events = Lists.reverse(trikeEvents);

        boolean causeOfDelay = false;

        int index = -1;

        //  questioner
        LocalDateTime eventTimeOfQuestionerTripCreation = null;
        LocalDateTime questionerTripStartTime = null;


        // predecessor
        String predecessorTripID = null;
        LocalDateTime eventTimeOfPredecessorTripCreation = null;
        LocalDateTime predecessorTripEndTime = null;


        //  search for of the trip of the customer
        for (int i = 0; i < events.size(); i++) {
            Event<?> event = events.get(i);
            String name = event.content.data.name;

            if("CustomerTripCreation".equalsIgnoreCase(name)){
                Map<String, Object> actions = event.content.data.actions;
                Trip trip = (Trip) actions.get("Create new CustomerTrip");
                String tripID = trip.getTripID();



                if (tripID.equals(questionerTripID)) {
                    eventTimeOfQuestionerTripCreation = event.updated;
                    questionerTripStartTime = trip.getDecisionTask().getVATimeFromJob();
                    index = i;
                    break;
                }
            }
        }

        if (eventTimeOfQuestionerTripCreation == null) {
            System.out.println("eventTimeOfQuestionerTripCreation is null");
            return false;
        }

        // search for the predecessor trip
        for (int i = index + 1; i < events.size(); i++) {
            Event<?> event = events.get(i);
            String name = event.content.data.name;

            if ("CustomerTripCreation".equalsIgnoreCase(name)) {
                Map<String, Object> actions = event.content.data.actions;
                Trip trip = (Trip) actions.get("Create new CustomerTrip");
                String tripID = trip.getTripID();


                index = i;
                predecessorTripID = tripID;
                eventTimeOfPredecessorTripCreation = event.updated;
                break;
            }
        }

        if (predecessorTripID == null || eventTimeOfPredecessorTripCreation == null) {
            System.out.println("No predecessor found");
            return false;
        }

        // search for the most recent endtime of the predecessor trip
        for (int i = 0; i < index; i++) {
            Event<?> event = events.get(i);

            if ("TripList_BeliefUpdated".equalsIgnoreCase(event.summary)) {
                Data<?> data = event.content.data;

                // Convert newValue to JsonElement, then deserialize to List<Trip>
                JsonElement jsonElement = gson.toJsonTree(data.oldValue);
                Type tripListType = new TypeToken<List<Trip>>() {}.getType();
                List<Trip> trips = gson.fromJson(jsonElement, tripListType);
                boolean contains = false;

                for (Trip trip: trips) {
                    if(trip.tripID.equals(predecessorTripID)){
                        contains = true;
                        predecessorTripEndTime = trip.endTime;
                    }
                }

                if (contains) {
                    if (predecessorTripEndTime.isAfter(questionerTripStartTime)) {
                        causeOfDelay = true;
                    }
                    break;
                }
            }
        }

        return causeOfDelay;
    }

    public static boolean isCharginTripCause(String questionerTripID, List<Event<?>> trikeEvents) {
        boolean causeOfDelay = false;

        List<Event<?>> events = Lists.reverse(trikeEvents);

        int index = -1;

        //  questioner
        LocalDateTime eventTimeOfQuestionerTripCreation = null;
        LocalDateTime questionerTripStartTime = null;


        // predecessor
        String predecessorTripID = null;
        LocalDateTime eventTimeOfPredecessorTripCreation = null;
        LocalDateTime predecessorTripEndTime = null;


        //  search for of the trip of the customer
        for (int i = 0; i < events.size(); i++) {
            Event<?> event = events.get(i);
            String name = event.content.data.name;

            if("CustomerTripCreation".equalsIgnoreCase(name)){
                Map<String, Object> actions = event.content.data.actions;
                Trip trip = (Trip) actions.get("Create new CustomerTrip");
                String tripID = trip.getTripID();


                if (tripID.equals(questionerTripID)) {
                    eventTimeOfQuestionerTripCreation = event.updated;
                    questionerTripStartTime = trip.getDecisionTask().getVATimeFromJob();
                    index = i;
                    break;
                }
            }
        }

        if (eventTimeOfQuestionerTripCreation == null) {
            System.out.println("eventTimeOfQuestionerTripCreation is null");
            return false;
        }

        // search for the predecessor trip
        for (int i = index + 1; i < events.size(); i++) {
            Event<?> event = events.get(i);
            String name = event.content.data.name;

            if ("chargingTripCreation".equalsIgnoreCase(name)) {
                Map<String, Object> actions = event.content.data.actions;
                Trip trip = (Trip) actions.get("Create new ChargingTrip");
                String tripID = trip.getTripID();

                index = i;
                predecessorTripID = tripID;
                eventTimeOfPredecessorTripCreation = event.updated;
                break;
            }
        }

        if (predecessorTripID == null || eventTimeOfPredecessorTripCreation == null) {
            System.out.println("No predecessor found");
            return false;
        }

        // search for the most recent endtime of the predecessor trip
        for (int i = 0; i < index; i++) {
            Event<?> event = events.get(i);

            if ("TripList_BeliefUpdated".equalsIgnoreCase(event.summary)) {
                Data<?> data = event.content.data;

                // Convert newValue to JsonElement, then deserialize to List<Trip>
                JsonElement jsonElement = gson.toJsonTree(data.oldValue);
                Type tripListType = new TypeToken<List<Trip>>() {}.getType();
                List<Trip> trips = gson.fromJson(jsonElement, tripListType);
                boolean contains = false;

                for (Trip trip: trips) {
                    if(trip.tripID.equals(predecessorTripID)){
                        contains = true;
                        predecessorTripEndTime = trip.endTime;
                    }
                }

                if (contains) {
                    if (predecessorTripEndTime.isAfter(questionerTripStartTime)) {
                        causeOfDelay = true;
                    }
                    break;
                }
            }
        }

        return causeOfDelay;
    }
}
