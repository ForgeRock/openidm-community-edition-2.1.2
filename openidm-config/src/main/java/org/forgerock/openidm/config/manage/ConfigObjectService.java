/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL
 * Header, with the fields enclosed by brackets [] replaced by your own identifying
 * information: "Portions Copyrighted [year] [name of copyright owner]".
 *
 * Copyright © 2011 ForgeRock AS. All rights reserved.
 */

package org.forgerock.openidm.config.manage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.Service;

import org.forgerock.json.fluent.JsonValue;
import org.forgerock.openidm.config.crypto.ConfigCrypto;
import org.forgerock.openidm.core.ServerConstants;
import org.forgerock.openidm.config.JSONEnhancedConfig;
import org.forgerock.openidm.config.installer.JSONConfigInstaller;
import org.forgerock.openidm.config.persistence.ConfigBootstrapHelper;
import org.forgerock.openidm.metadata.WaitForMetaData;

import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.component.ComponentContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Deprecated
import org.forgerock.openidm.objset.BadRequestException;
import org.forgerock.openidm.objset.ConflictException;
import org.forgerock.openidm.objset.ForbiddenException;
import org.forgerock.openidm.objset.InternalServerErrorException;
import org.forgerock.openidm.objset.NotFoundException;
import org.forgerock.openidm.objset.ObjectSet;
import org.forgerock.openidm.objset.ObjectSetException;
import org.forgerock.openidm.objset.ObjectSetJsonResource;
import org.forgerock.openidm.objset.Patch;
import org.forgerock.openidm.objset.PreconditionFailedException;

/**
 * Provides access to OSGi configuration
 *
 * @author aegloff
 */
@Component(
        name = "org.forgerock.openidm.config",
        immediate = true,
        policy = ConfigurationPolicy.OPTIONAL
)
@Properties({
        @Property(name = "service.description", value = "OpenIDM configuration service"),
        @Property(name = "service.vendor", value = "ForgeRock AS"),
        @Property(name = ServerConstants.ROUTER_PREFIX, value = "config")
})
@Service
public class ConfigObjectService extends ObjectSetJsonResource {

    final static Logger logger = LoggerFactory.getLogger(ConfigObjectService.class);

    @Reference
    ConfigurationAdmin configAdmin;

    private ComponentContext context;
    private ConfigCrypto configCrypto;

    /**
     * Gets an object from the object set by identifier.
     * <p/>
     * The object may contain metadata properties, including object identifier {@code _id},
     * and object version {@code _rev} to enable optimistic concurrency supported by OpenIDM.
     *
     * @param fullId the identifier of the object to retrieve from the object set.
     * @return the requested object.
     * @throws NotFoundException   if the specified object could not be found.
     * @throws ForbiddenException  if access to the object is forbidden.
     * @throws BadRequestException if the passed identifier is invalid
     */
    @Override
    public Map<String, Object> read(String fullId) throws ObjectSetException {
        logger.debug("Invoking read {}", fullId);
        Map<String, Object> result = null;

        try {
            if (fullId == null) {
                // List all configurations
                result = new HashMap<String, Object>();
                Configuration[] rawConfigs = configAdmin.listConfigurations(null);
                List configList = new ArrayList();
                if (null != rawConfigs) {
                    for (Configuration conf : rawConfigs) {
                        Map<String, Object> configEntry = new LinkedHashMap<String, Object>();

                        String alias = null;
                        Dictionary properties = conf.getProperties();
                        if (properties != null) {
                            alias = (String) properties.get(JSONConfigInstaller.SERVICE_FACTORY_PID_ALIAS);
                        }
                        String pid = ConfigBootstrapHelper.unqualifyPid(conf.getPid());
                        String factoryPid = ConfigBootstrapHelper.unqualifyPid(conf.getFactoryPid());
                        String id = null;
                        // If there is an alias for factory config is available, make a nicer ID then the internal PID
                        if (factoryPid != null && alias != null) {
                            id = factoryPid + "/" + alias;
                        } else {
                            id = pid;
                        }

                        configEntry.put("_id", id);
                        configEntry.put("pid", pid);
                        configEntry.put("factoryPid", factoryPid);
                        configList.add(configEntry);
                    }
                }
                result.put("configurations", configList);
                logger.debug("Read list of configurations with {} entries", configList.size());
            } else {
                Configuration config = findExistingConfiguration(fullId);
                if (config == null) {
                    throw new NotFoundException("No configuration exists for id " + fullId);
                }
                Dictionary props = config.getProperties();
                JSONEnhancedConfig enhancedConfig = new JSONEnhancedConfig();
                JsonValue value = enhancedConfig.getConfiguration(props, context.getBundleContext(), fullId, false);
                result = value.asMap();
                logger.debug("Read configuration for service {}", fullId);
            }
        } catch (ObjectSetException ex) {
            throw ex;
        } catch (Exception ex) {
            logger.warn("Failure to load configuration for {}", fullId, ex);
            throw new InternalServerErrorException("Failure to load configuration for " + fullId + ": " + ex.getMessage(), ex);
        }
        return result;
    }

    /**
     * Creates a new object in the object set.
     * <p/>
     * This method sets the {@code _id} property to the assigned identifier for the object,
     * and the {@code _rev} property to the revised object version (For optimistic concurrency)
     *
     * @param fullId the client-generated identifier to use, or {@code null} if server-generated identifier is requested.
     * @param obj    the contents of the object to create in the object set.
     * @throws NotFoundException           if the specified id could not be resolved.
     * @throws ForbiddenException          if access to the object or object set is forbidden.
     * @throws PreconditionFailedException if an object with the same ID already exists.
     * @throws BadRequestException         if the passed identifier is invalid
     */
    @Override
    public void create(String fullId, Map<String, Object> obj) throws ObjectSetException {
        logger.debug("Invoking create configuration {} {}", fullId, obj);
        if (fullId == null) {
            throw new BadRequestException("The passed identifier to create is null");
        }
        ParsedId parsedId = new ParsedId(fullId);
        try {
            Configuration config = null;
            if (parsedId.isFactoryConfig()) {
                String qualifiedFactoryPid = ParsedId.qualifyPid(parsedId.factoryPid);
                if ("org.forgerock.openidm.router".equalsIgnoreCase(qualifiedFactoryPid)) {
                    throw new BadRequestException("router config can not be factory config");
                }
                config = configAdmin.createFactoryConfiguration(qualifiedFactoryPid, null);
            } else {
                String qualifiedPid = ParsedId.qualifyPid(parsedId.pid);
                config = configAdmin.getConfiguration(qualifiedPid, null);
            }
            if (config.getProperties() != null) {
                throw new PreconditionFailedException("Can not create a new configuration with ID "
                        + parsedId + ", configuration for this ID already exists.");
            }

            Dictionary dict = configCrypto.encrypt(parsedId.getPidOrFactoryPid(), parsedId.instanceAlias, null, new JsonValue(obj));
            if (parsedId.isFactoryConfig()) {
                dict.put(JSONConfigInstaller.SERVICE_FACTORY_PID_ALIAS, parsedId.instanceAlias); // The alias for the PID as understood by fileinstall
            }

            config.update(dict);
            logger.debug("Created new configuration for {} with {}", fullId, dict);
        } catch (ObjectSetException ex) {
            throw ex;
        } catch (WaitForMetaData ex) {
            logger.info("No meta-data provider available yet to create and encrypt configuration for {}, retry later.", fullId, ex);
            throw new InternalServerErrorException("No meta-data provider available yet to create and encrypt configuration for "
                    + fullId + ", retry later.", ex);
        } catch (Exception ex) {
            logger.warn("Failure to create configuration for {}", fullId, ex);
            throw new InternalServerErrorException("Failure to create configuration for " + fullId + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Updates the specified object in the object set.
     * <p/>
     * This implementation requires MVCC and hence enforces that clients state what revision they expect
     * to be updating
     * <p/>
     * If successful, this method updates metadata properties within the passed object,
     * including: a new {@code _rev} value for the revised object's version
     *
     * @param fullId the identifier of the object to be put, or {@code null} to request a generated identifier.
     * @param rev    the version of the object to update; or {@code null} if not provided.
     * @param obj    the contents of the object to put in the object set.
     * @throws ConflictException           if version is required but is {@code null}.
     * @throws ForbiddenException          if access to the object is forbidden.
     * @throws NotFoundException           if the specified object could not be found.
     * @throws PreconditionFailedException if version did not match the existing object in the set.
     * @throws BadRequestException         if the passed identifier is invalid
     */
    @Override
    public void update(String fullId, String rev, Map<String, Object> obj) throws ObjectSetException {
        logger.debug("Invoking update configuration {} {}", fullId, rev);
        if (fullId == null) {
            throw new BadRequestException("The passed identifier to update is null");
        }
        try {
            ParsedId parsedId = new ParsedId(fullId);
            Configuration config = findExistingConfiguration(fullId);

            Dictionary existingConfig = (config == null ? null : config.getProperties());
            if (existingConfig == null) {
                throw new NotFoundException("No existing configuration found for " + fullId + ", can not update the configuration.");
            }
            existingConfig = configCrypto.encrypt(parsedId.getPidOrFactoryPid(), parsedId.instanceAlias, existingConfig, new JsonValue(obj));
            config.update(existingConfig);
            logger.debug("Updated existing configuration for {} with {}", fullId, existingConfig);
        } catch (ObjectSetException ex) {
            throw ex;
        } catch (Exception ex) {
            logger.warn("Failure to update configuration for {}", fullId, ex);
            throw new InternalServerErrorException("Failure to update configuration for " + fullId + ": " + ex.getMessage(), ex);
        }
    }


    /**
     * Deletes the specified object from the object set.
     *
     * @param fullId the identifier of the object to be deleted.
     * @param rev    the version of the object to delete or {@code null} if not provided.
     * @throws NotFoundException           if the specified object could not be found.
     * @throws ForbiddenException          if access to the object is forbidden.
     * @throws ConflictException           if version is required but is {@code null}.
     * @throws PreconditionFailedException if version did not match the existing object in the set.
     */
    @Override
    public void delete(String fullId, String rev) throws ObjectSetException {
        logger.debug("Invoking delete configuration {} {}", fullId, rev);
        if (fullId == null) {
            throw new BadRequestException("The passed identifier to delete is null");
        }
        try {
            Configuration config = findExistingConfiguration(fullId);

            Dictionary existingConfig = config.getProperties();
            if (existingConfig == null) {
                throw new NotFoundException("No existing configuration found for " + fullId + ", can not delete the configuration.");
            }
            config.delete();
            logger.debug("Deleted configuration for {}", fullId);
        } catch (ObjectSetException ex) {
            throw ex;
        } catch (Exception ex) {
            logger.warn("Failure to delete configuration for {}", fullId, ex);
            throw new InternalServerErrorException("Failure to delete configuration for " + fullId + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Locate an existing configuration based on its id, which can be
     * a pid or for factory configurations the <factory pid>/<alias>
     * pids can be qualified or if they use the default openidm prefix unqualified
     *
     * @param fullId the id
     * @return the configuration if found, null if not
     * @throws IOException
     * @throws InvalidSyntaxException
     */
    Configuration findExistingConfiguration(String fullId) throws IOException, InvalidSyntaxException, BadRequestException {
        ParsedId parsedId = new ParsedId(fullId);

        String filter = null;
        if (parsedId.isFactoryConfig()) {
            String factoryPid = ParsedId.qualifyPid(parsedId.factoryPid);
            filter = "(&(" + ConfigurationAdmin.SERVICE_FACTORYPID + "=" + factoryPid + ")(" + JSONConfigInstaller.SERVICE_FACTORY_PID_ALIAS + "=" + parsedId.instanceAlias + "))";
        } else {
            String pid = ParsedId.qualifyPid(parsedId.pid);
            filter = "(" + Constants.SERVICE_PID + "=" + pid + ")";
        }
        logger.trace("List configurations with filter: {}", filter);
        Configuration[] configurations = configAdmin.listConfigurations(filter);
        logger.debug("Configs found: {}", configurations);
        if (configurations != null && configurations.length > 0) {
            return configurations[0];
        } else {
            return null;
        }
    }

    @Activate
    protected void activate(ComponentContext context) {
        logger.debug("Activating configuration management service");
        this.context = context;
        this.configCrypto = ConfigCrypto.getInstance(context.getBundleContext(), null);
    }

    /**
     * TODO: Description.
     *
     * @param context TODO.
     */
    @Deactivate
    protected void deactivate(ComponentContext context) {
        logger.debug("Deactivating configuration management service");
    }
}

class ParsedId {
    final static Logger logger = LoggerFactory.getLogger(ParsedId.class);

    public String pid;
    public String factoryPid;
    public String instanceAlias;

    public ParsedId(String fullId) throws BadRequestException {
        String[] clauses = fullId.split("/");
        if (0 == clauses.length || clauses.length > 2) {
            throw new BadRequestException("The passed identifier to has more then one '/'");
        }
        switch (clauses.length) {
            case 2: {
                // Do blank check StringUtils.isBlank()
                if ((null != clauses[1]) && (!"".equals(clauses[1].trim()))) {
                    instanceAlias = clauses[1].trim();
                }
            }
            case 1: {
                if ((null == clauses[0]) || ("".equals(clauses[0].trim()))) {
                    throw new BadRequestException("The passed Factory identifier has no pid");
                } else {
                    if (null == instanceAlias) {
                        pid = clauses[0].trim();
                    } else {
                        factoryPid = clauses[0].trim();
                    }
                }
            }
        }
        if (null != factoryPid) {
            logger.trace("Factory configuration pid: {} instance alias: {}", factoryPid, instanceAlias);
        } else {
            logger.trace("Managed service configuration pid: {}", pid);
        }
    }

    /**
     * @return is this ID represents a managed factory configuration, or false if it is a managed service configuraiton
     */
    public boolean isFactoryConfig() {
        return (instanceAlias != null);
    }

    /*
    * Make the PID fully qualified with the default context for OpenIDM
    */
    public static String qualifyPid(String pid) {
        return ConfigBootstrapHelper.qualifyPid(pid);
    }

    /**
     * Get the qualified pid of the managed service or managed factory depending on the configuration represented
     * Some APIs do not distinguish beween single managed service PID and managed factory PID
     *
     * @return the qualified pid if this ID represents a managed service configuration, or the managed factory PID
     *         if it represents a managed factory configuration
     */
    public String getPidOrFactoryPid() {
        if (isFactoryConfig()) {
            return qualifyPid(factoryPid);
        } else {
            return qualifyPid(pid);
        }
    }

    public String toString() {
        if (isFactoryConfig()) {
            return factoryPid + "-" + instanceAlias;
        } else {
            return pid;
        }
    }
}
