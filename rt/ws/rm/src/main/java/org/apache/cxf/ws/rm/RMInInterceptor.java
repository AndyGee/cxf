/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.ws.rm;

import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageUtils;
import org.apache.cxf.ws.addressing.AddressingProperties;
import org.apache.cxf.ws.addressing.ContextUtils;
import org.apache.cxf.ws.addressing.MAPAggregator;
import org.apache.cxf.ws.rm.v200702.Identifier;
import org.apache.cxf.ws.rm.v200702.SequenceAcknowledgement;

/**
 * 
 */
public class RMInInterceptor extends AbstractRMInterceptor<Message> {
    
    private static final Logger LOG = LogUtils.getL7dLogger(RMInInterceptor.class);
  
    public RMInInterceptor() {
 
        addBefore(MAPAggregator.class.getName());
    }
   
    @Override
    public void handleFault(Message message) {
        message.put(MAPAggregator.class.getName(), true);
        if (RMContextUtils.getProtocolVariation(message) != null
            && MessageUtils.isTrue(message.get(RMMessageConstants.DELIVERING_ROBUST_ONEWAY))) {
            // revert the delivering entry from the destination sequence
            try {
                Destination destination = getManager().getDestination(message);
                if (destination != null) {
                    destination.releaseDeliveringStatus(message);
                }
            } catch (RMException e) {
                LOG.log(Level.WARNING, "Failed to revert the delivering status");
            }
        }
        // make sure the fault is returned for an ws-rm related fault or an invalid ws-rm message
        // note that OneWayProcessingInterceptor handles the robust case, hence not handled here.
        if (isProtocolFault(message)
            && !MessageUtils.isTrue(message.get(RMMessageConstants.DELIVERING_ROBUST_ONEWAY))) {
            Exchange exchange = message.getExchange();
            exchange.setOneWay(false);

            final AddressingProperties maps = ContextUtils.retrieveMAPs(message, false, false, true);
            if (maps != null && !ContextUtils.isGenericAddress(maps.getFaultTo())) {
                //TODO look at how we can refactor all these decoupled faultTo stuff
                exchange.setDestination(ContextUtils.createDecoupledDestination(exchange, maps.getFaultTo()));
            }
        }
    }

    private boolean isProtocolFault(Message message) {
        return !ContextUtils.isRequestor(message)
            && (RMContextUtils.getProtocolVariation(message) == null
                || message.getContent(Exception.class) instanceof SequenceFault);
    }

    protected void handle(Message message) throws SequenceFault, RMException {
        LOG.entering(getClass().getName(), "handleMessage");
        
        boolean isServer = RMContextUtils.isServerSide(message);
        LOG.fine("isServerSide: " + isServer);

        RMProperties rmps = RMContextUtils.retrieveRMProperties(message, false);
        // message addressing properties may be null, e.g. in case of a runtime fault 
        // on the server side
        final AddressingProperties maps = ContextUtils.retrieveMAPs(message, false, false, false);
        if (null == maps) {
            //if wsrmp:RMAssertion and addressing is optional
            if (isServer && !isRMPolicyEnabled(message)) {
                org.apache.cxf.common.i18n.Message msg = new org.apache.cxf.common.i18n.Message(
                    "WSA_REQUIRED_EXC", LOG);
                LOG.log(Level.INFO, msg.toString());
                throw new RMException(msg);                
            } else {
                return;
            }
        }

        String action = null;
        if (null != maps.getAction()) {
            action = maps.getAction().getValue();
        }

        if (LOG.isLoggable(Level.FINE)) {
            LOG.fine("Action: " + action);
        }
        
        Object originalRequestor = message.get(RMMessageConstants.ORIGINAL_REQUESTOR_ROLE);
        if (null != originalRequestor) {
            LOG.fine("Restoring original requestor role to: " + originalRequestor);
            message.put(Message.REQUESTOR_ROLE, originalRequestor);
        }

        // get the wsa and wsrm namespaces from the message 
        String rmUri = rmps.getNamespaceURI();
        String addrUri = maps.getNamespaceURI();

        ProtocolVariation protocol = ProtocolVariation.findVariant(rmUri, addrUri);
        if (null == protocol && !MessageUtils.isFault(message)) {
            org.apache.cxf.common.i18n.Message msg = new org.apache.cxf.common.i18n.Message(
                "WSRM_REQUIRED_EXC", LOG, rmUri, addrUri);
            LOG.log(Level.INFO, msg.toString());
            throw new RMException(msg);
        }
        RMContextUtils.setProtocolVariation(message, protocol);
        
        // Destination destination = getManager().getDestination(message);
        // RMEndpoint rme = getManager().getReliableEndpoint(message);
        // Servant servant = new Servant(rme);
        
        boolean isApplicationMessage = !RMContextUtils.isRMProtocolMessage(action);
        LOG.fine("isApplicationMessage: " + isApplicationMessage);
        
        // for application AND out of band messages
        
        RMEndpoint rme = getManager().getReliableEndpoint(message);
        Destination destination = getManager().getDestination(message);
        
        if (isApplicationMessage) {                        
            if (null != rmps) {
                processAcknowledgments(rme, rmps, protocol);
                processAcknowledgmentRequests(destination, message);
                processSequence(destination, message);
                processDeliveryAssurance(rmps);
            }
            if (ContextUtils.retrieveDeferredUncorrelatedMessageAbort(message)) {
                LOG.info("deferred uncorrelated message abort");
                message.getInterceptorChain().abort();
            } else {
                rme.receivedApplicationMessage();
            }
        } else {
            rme.receivedControlMessage();
            if (RM10Constants.SEQUENCE_ACKNOWLEDGMENT_ACTION.equals(action)
                || RM11Constants.SEQUENCE_ACKNOWLEDGMENT_ACTION.equals(action)) {
                processAcknowledgments(rme, rmps, protocol);
            } else if (RM10Constants.CLOSE_SEQUENCE_ACTION.equals(action)
                || RM11Constants.SEQUENCE_ACKNOWLEDGMENT_ACTION.equals(action)) {
                processSequence(destination, message);
            } else if ((RM10Constants.CREATE_SEQUENCE_ACTION.equals(action)
                || RM11Constants.CREATE_SEQUENCE_ACTION.equals(action)) && !isServer) {
                LOG.fine("Processing inbound CreateSequence on client side.");
                Servant servant = rme.getServant();
                Object csr = servant.createSequence(message);
                Proxy proxy = rme.getProxy();
                proxy.createSequenceResponse(csr, protocol);
                return;
            }
        }
        
        assertReliability(message);
    }
    
    void processAcknowledgments(RMEndpoint rme, RMProperties rmps, ProtocolVariation protocol) 
        throws SequenceFault, RMException {
        
        Collection<SequenceAcknowledgement> acks = rmps.getAcks();
        Source source = rme.getSource();
        if (null != acks) {
            for (SequenceAcknowledgement ack : acks) {
                Identifier id = ack.getIdentifier();
                SourceSequence ss = source.getSequence(id);                
                if (null != ss) {
                    ss.setAcknowledged(ack);
                } else {
                    RMConstants consts = protocol.getConstants();
                    SequenceFaultFactory sff = new SequenceFaultFactory(consts);
                    throw sff.createUnknownSequenceFault(id);
                }
            }
        }
    }

    void processAcknowledgmentRequests(Destination destination, Message message) 
        throws SequenceFault, RMException {
        destination.ackRequested(message); 
    }
    
    void processSequence(Destination destination, Message message) 
        throws SequenceFault, RMException {
        final boolean robust =
            MessageUtils.isTrue(message.getContextualProperty(Message.ROBUST_ONEWAY));
        if (robust) {
            // set this property to change the acknlowledging behavior
            message.put(RMMessageConstants.DELIVERING_ROBUST_ONEWAY, Boolean.TRUE);
        }
        destination.acknowledge(message);
    }
    
    void processDeliveryAssurance(RMProperties rmps) {
        
    }
}
