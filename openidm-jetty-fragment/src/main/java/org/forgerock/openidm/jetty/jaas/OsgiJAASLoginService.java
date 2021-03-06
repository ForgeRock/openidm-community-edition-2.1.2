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
package org.forgerock.openidm.jetty.jaas;

import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.plus.jaas.JAASLoginService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Login service to give us additional control over how JAAS is handled in 
 * the context of an OSGi environment.
 * 
 * @author aegloff
 */
public class OsgiJAASLoginService extends JAASLoginService {
    
    final static Logger logger = LoggerFactory.getLogger(OsgiJAASLoginService.class);
    
    public OsgiJAASLoginService() {
        /* 
            The following packages and sample content must be loadable from the thread context classloader
            org.eclipse.jetty.plus.jaas.JAASRole
            org.eclipse.jetty.plus.jaas.callback.DefaultCallbackHandler
            org.eclipse.jetty.plus.jaas.spi.UserInfo
            
            Dynamic import in fragment used for the following to allow thread context classloader to find the login module
            org.forgerock.openidm.jaas.RepoLoginModule
        */
    }

    /** 
     * @InheritDoc
     */
    public UserIdentity login(final String username, final Object credentials) {

        // JAAS heavily relies on thread context classloaders.
        // Set it to the jetty bundle, which defines the Role classes etc.
        // This requires that the login module is loadable from the jetty bundle
        ClassLoader origTCCL = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(JAASLoginService.class.getClassLoader());
            logger.trace("Invoking login for {} with context cl {}", username,  Thread.currentThread().getContextClassLoader());
            return super.login(username, credentials);
        } finally {
            Thread.currentThread().setContextClassLoader(origTCCL);
        }
    }
}

