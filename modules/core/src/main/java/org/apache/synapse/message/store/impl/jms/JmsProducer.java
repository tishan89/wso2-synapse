/**
 *  Copyright (c) 2005-2010, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.synapse.message.store.impl.jms;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.message.MessageProducer;

import javax.jms.*;
import java.util.Set;

public class JmsProducer implements MessageProducer {
    private static final String JMS_PROD_TIME_TO_LIVE = "JMS_PROD_TIME_TO_LIVE";
    private static final String JMS_PROD_DISABLE_MSG_TIMESTAMP = "JMS_PROD_DISABLE_MSG_TIMESTAMP";
    private static final String JMS_PROD_DELIVERY_MODE = "JMS_PROD_DELIVERY_MODE";
    private static final String JMS_PROD_DISABLE_MSG_ID = "JMS_PROD_DISABLE_MSG_ID";
    private static final String JMS_PROD_PRIORITY = "JMS_PROD_PRIORITY";
    // prefix used to set JMS Message level properties. ex: JMS_MSG_P_brokerSpecificProperty
    private static final String JMS_MSG_P = "JMS_MSG_P_";
    private static final Log logger = LogFactory.getLog(JmsProducer.class.getName());

    private static final String OriginalMessageID = "OrigMessageID";

    private Connection connection;

    private Session session;

    private javax.jms.MessageProducer producer;

    private JmsStore store;

    private String idString;

    private boolean isConnectionError = false;

    private boolean isInitialized = false;

    public JmsProducer(JmsStore store) {
        if (store == null) {
            logger.error("Cannot initialize.");
            return;
        }
        this.store = store;
        isInitialized = true;
    }

    public boolean storeMessage(MessageContext synCtx) {
        if (synCtx == null) {
            return false;
        }
        if (!checkConnection()) {
            logger.warn(getId() + ". Ignored MessageID : " + synCtx.getMessageID());
            return false;
        }
        StorableMessage message = MessageConverter.toStorableMessage(synCtx);
        boolean error = false;
        Throwable throwable = null;
        try {
            ObjectMessage objectMessage = session.createObjectMessage(message);
            objectMessage.setStringProperty(OriginalMessageID, synCtx.getMessageID());
            setPriority(producer, objectMessage, message);
            setJmsProducerProperties(producer, synCtx);
            setJmsMessageProperties(objectMessage, synCtx);
            producer.send(objectMessage);
        } catch (JMSException e) {
            throwable = e;
            error = true;
            isConnectionError = true;
        } catch (Throwable t) {
            throwable = t;
            error = true;
        }
        if (error) {
            String errorMsg = getId() + ". Ignored MessageID : " + synCtx.getMessageID()
                              + ". Could not store message to store ["
                              + store.getName() + "]. Error:" + throwable.getLocalizedMessage();
            logger.error(errorMsg, throwable);
            store.closeWriteConnection();
            connection = null;
            if (logger.isDebugEnabled()) {
                logger.debug(getId() + ". Ignored MessageID : " + synCtx.getMessageID());
            }
            return false;
        } else {
            store.cleanup(null, session, false);
        }
        if (logger.isDebugEnabled()) {
            logger.debug(getId() + ". Stored MessageID : " + synCtx.getMessageID());
        }
        store.enqueued();
        return true;
    }

    public boolean cleanup() {
        return store.cleanup(null, session, false);
    }

    public JmsProducer setConnection(Connection connection) {
        this.connection = connection;
        return this;
    }

    public JmsProducer setSession(Session session) {
        this.session = session;
        return this;
    }

    public JmsProducer setProducer(javax.jms.MessageProducer producer) {
        this.producer = producer;
        return this;
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public void setId(int id) {
        idString = "[" + store.getName() + "-P-" + id + "]";
    }

    public String getId() {
        return getIdAsString();
    }

    private String getIdAsString() {
        if (idString == null) {
            return "[unknown-producer]";
        }
        return idString;
    }

    private boolean checkConnection() {
        if (producer == null) {
            if (logger.isDebugEnabled()) {
                logger.error(getId() + " cannot proceed. Message producer is null.");
            }
            return false;
        }
        if (session == null) {
            if (logger.isDebugEnabled()) {
                logger.error(getId() + " cannot proceed. JMS Session is null.");
            }
            return false;
        }
        if (connection == null) {
            if (logger.isDebugEnabled()) {
                logger.error(getId() + " cannot proceed. JMS Connection is null.");
            }
            return false;
        }
        return true;
    }

    private void setPriority(javax.jms.MessageProducer producer, ObjectMessage objectMessage,
                             StorableMessage message) {
        if (message.getPriority() != Message.DEFAULT_PRIORITY) {
            try {
                producer.setPriority(message.getPriority());
            } catch (JMSException e) {
                logger.warn(getId() + " could not set priority ["
                            + message.getPriority() + "]");
            }
        }  else {
            try {
                producer.setPriority(Message.DEFAULT_PRIORITY);
            } catch (JMSException e) {}
        }
    }

    private void setJmsProducerProperties(javax.jms.MessageProducer producer, MessageContext synCtx) {
        Object prop = synCtx.getProperty(JMS_PROD_TIME_TO_LIVE);
        long ttl = Message.DEFAULT_TIME_TO_LIVE;
        if (prop instanceof String) {
            ttl = Long.parseLong((String) prop);
        } else if (prop instanceof Long || prop instanceof Integer) {
            ttl = Long.parseLong(prop.toString());
        }
        prop = synCtx.getProperty(JMS_PROD_DISABLE_MSG_TIMESTAMP);
        boolean disableMessageTimestamp = false;
        if (prop instanceof String) {
            disableMessageTimestamp = Boolean.parseBoolean((String) prop);
        } else if (prop instanceof Boolean) {
            disableMessageTimestamp = (Boolean) prop;
        }
        prop = synCtx.getProperty(JMS_PROD_DELIVERY_MODE);
        int deliveryMode = Message.DEFAULT_DELIVERY_MODE;
        if (prop instanceof Integer) {
            deliveryMode = (Integer) prop;
        } else if (prop instanceof String) {
            deliveryMode = Integer.parseInt((String) prop);
        }
        prop = synCtx.getProperty(JMS_PROD_DISABLE_MSG_ID);
        boolean disableMessageId = false;
        if (prop instanceof String) {
            disableMessageId = Boolean.parseBoolean((String) prop);
        } else if (prop instanceof Boolean) {
            disableMessageId = (Boolean) prop;
        }
        prop = synCtx.getProperty(JMS_PROD_PRIORITY);
        int priority = Message.DEFAULT_PRIORITY;
        if (prop instanceof Integer) {
            priority = (Integer) prop;
        } else if (prop instanceof String) {
            priority = Integer.parseInt((String) prop);
        }
        try {
            producer.setTimeToLive(ttl);
            producer.setDisableMessageTimestamp(disableMessageTimestamp);
            producer.setDeliveryMode(deliveryMode);
            producer.setDisableMessageID(disableMessageId);
            producer.setPriority(priority);
        } catch (JMSException e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Could not save Producer property: " + e.getLocalizedMessage());
            }
        }
    }

    private void setJmsMessageProperties(Message message, MessageContext synCtx) {
        Set<String> properties = synCtx.getPropertyKeySet();
        for (String prop : properties) {
            if (prop.startsWith(JMS_MSG_P)) {
                Object value = synCtx.getProperty(prop);
                String key = prop.substring(JMS_MSG_P.length());
                try {
                    if (value instanceof String) {
                        message.setStringProperty(key, (String) value);
                    } else if (value instanceof Long) {
                        message.setLongProperty(key, (Long) value);
                    } else if (value instanceof Integer) {
                        message.setIntProperty(key, (Integer) value);
                    } else if (value instanceof Boolean) {
                        message.setBooleanProperty(key, (Boolean) value);
                    } else if (value instanceof Double) {
                        message.setDoubleProperty(key, (Double) value);
                    } else if (value instanceof Float) {
                        message.setFloatProperty(key, (Float) value);
                    } else if (value instanceof Short) {
                        message.setShortProperty(key, (Short) value);
                    }
                } catch (JMSException e) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Could not save Message property: " + e.getLocalizedMessage());
                    }
                }
            }
        }

    }
}