/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright © 2012 ForgeRock Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 */
package org.forgerock.openidm.workflow.activiti.impl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.activiti.engine.impl.context.Context;
import org.activiti.engine.impl.identity.Authentication;
import org.forgerock.json.fluent.JsonException;
import org.forgerock.json.fluent.JsonTransformer;
import org.forgerock.json.fluent.JsonValue;
import org.forgerock.json.resource.JsonResourceContext;
import org.forgerock.openidm.audit.util.ActivityLog;
import org.forgerock.openidm.objset.ObjectSetContext;
import org.forgerock.openidm.util.DateUtil;
import org.joda.time.DateTime;

/**
 * Utility class for Activiti workflow integration
 * @author orsolyamebold
 */
public class ActivitiUtil {
    /**
     * Fetch and remove process key from the request
     * @param request Request to be processed
     * @return process key
     */
    public static String removeKeyFromRequest(JsonValue request) {
          return (String) (request.get("value").isNull() ? null : request.get("value").expect(Map.class).asMap().remove("_key"));
    }
    
    /**
     * Fetch and remove process business key from the request
     * @param request Request to be processed
     * @return process business key
     */
    public static String removeBusinessKeyFromRequest(JsonValue request) {
          return (String) (request.get("value").isNull() ? null : request.get("value").expect(Map.class).asMap().remove("_businessKey"));
    }
    
    /**
     * Fetch and remove Activiti workflow processDefinitionId if present
     * @param request Request to be processed
     * @return processDefinitionId
     */
    public static String removeProcessDefinitionIdFromRequest(JsonValue request) {
        return (String) (request.get("value").isNull() ? null : request.get("value").expect(Map.class).asMap().remove("_processDefinitionId"));
    }
    
    /**
     * Fetch the body of the request
     * @param request Request to be processed
     * @return request body
     */
    public static Map<String, Object> getRequestBodyFromRequest(JsonValue request) {
        if (!request.get("value").isNull()) {
            JsonValue val = request.get("value");
            val.getTransformers().add(new DatePropertyTransformer());
            val.applyTransformers();
            val = val.copy();
            return new HashMap<String, Object>(val.expect(Map.class).asMap());
        } else {
            return new HashMap(1);
        }
    }
    
    /**
     * 
     * @param request incoming request
     * @return 
     */
    public static String getIdFromRequest(JsonValue request) {
        String[] id = request.get("id").asString().split("/");
        return id[id.length-1];
    }
    
    public static String getQueryIdFromRequest(JsonValue request) {
        return request.get("params").get("_queryId").asString();
    }
    
    public static String getParamFromRequest(JsonValue request, String paramName) {
        return request.get(ActivitiConstants.REQUEST_PARAMS).get(paramName).asString();
    }

    private static class DatePropertyTransformer implements JsonTransformer {
        @Override
        public void transform(JsonValue value) throws JsonException {
            if (null != value && value.isString()) {
                DateTime d = DateUtil.getDateUtil().parseIfDate(value.asString());
                if (d != null){
                    value.setObject(d.toDate());
                }
            }
        }
    }
    
    public static JsonValue updateActivitiContext(String userName) {
        JsonValue context = JsonResourceContext.newContext("activiti", JsonResourceContext.newRootContext());
        HashMap<String, Object> security = new HashMap<String, Object>();
        security.put("username", userName);
        context.put("security", security);
        return context;
    }
    
    static void checkAndSetContext() {
        String userName = Authentication.getAuthenticatedUserId();
        JsonValue objectSetContext = ObjectSetContext.get();
                
        if (objectSetContext == null){  //async call
            JsonValue savedContext = (JsonValue) Context.getExecutionContext().getExecution().getVariable("openidmcontext");
            if (savedContext != null) {
                ObjectSetContext.push(savedContext);
                if (userName != null && !userName.equals(ActivityLog.getRequester(savedContext))){
                    ObjectSetContext.push(ActivitiUtil.updateActivitiContext(userName));
                }
            }
        } else {
            if (userName != null && !userName.equals(ActivityLog.getRequester(objectSetContext))){
                ObjectSetContext.push(ActivitiUtil.updateActivitiContext(userName));
            }
        }
    }
}
