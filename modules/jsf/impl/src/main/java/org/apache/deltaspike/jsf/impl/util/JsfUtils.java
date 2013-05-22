/*
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
package org.apache.deltaspike.jsf.impl.util;

import org.apache.deltaspike.core.api.config.view.metadata.ViewConfigResolver;
import org.apache.deltaspike.core.api.provider.BeanProvider;
import org.apache.deltaspike.core.api.config.view.navigation.NavigationParameterContext;
import org.apache.deltaspike.jsf.api.config.JsfModuleConfig;
import org.apache.deltaspike.jsf.impl.listener.phase.WindowMetaData;
import org.apache.deltaspike.jsf.impl.message.FacesMessageEntry;

import javax.enterprise.context.ContextNotActiveException;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

public abstract class JsfUtils
{
    public static <T> T getValueOfExpression(String expression, Class<T> targetType)
    {
        FacesContext facesContext = FacesContext.getCurrentInstance();
        return facesContext.getApplication().evaluateExpressionGet(facesContext, expression, targetType);
    }

    public static String getValueOfExpressionAsString(String expression)
    {
        Object result = getValueOfExpression(expression, Object.class);

        return result != null ? result.toString() : "null";
    }

    public static Set<RequestParameter> getViewConfigPageParameters()
    {
        ExternalContext externalContext = FacesContext.getCurrentInstance().getExternalContext();

        Set<RequestParameter> result = new HashSet<RequestParameter>();

        if (externalContext == null || //detection of early config for different mojarra versions
                externalContext.getRequestParameterValuesMap() == null || externalContext.getRequest() == null)
        {
            return result;
        }

        NavigationParameterContext navigationParameterContext =
                BeanProvider.getContextualReference(NavigationParameterContext.class);

        for (Map.Entry<String, String> entry : navigationParameterContext.getPageParameters().entrySet())
        {
            //TODO add multi-value support
            result.add(new RequestParameter(entry.getKey(), new String[]{entry.getValue()}));
        }

        return result;
    }

    /**
     * Adds the current request-parameters to the given url
     *
     * @param externalContext current external-context
     * @param url             current url
     * @param encodeValues    flag which indicates if parameter values should be encoded or not
     * @return url with request-parameters
     */
    public static String addPageParameters(ExternalContext externalContext, String url, boolean encodeValues)
    {
        StringBuilder finalUrl = new StringBuilder(url);
        boolean existingParameters = url.contains("?");

        for (RequestParameter requestParam : getViewConfigPageParameters())
        {
            String key = requestParam.getKey();

            for (String parameterValue : requestParam.getValues())
            {
                if (!url.contains(key + "=" + parameterValue) &&
                        !url.contains(key + "=" + encodeURLParameterValue(parameterValue, externalContext)))
                {
                    if (!existingParameters)
                    {
                        finalUrl.append("?");
                        existingParameters = true;
                    }
                    else
                    {
                        finalUrl.append("&");
                    }
                    finalUrl.append(key);
                    finalUrl.append("=");

                    if (encodeValues)
                    {
                        finalUrl.append(JsfUtils.encodeURLParameterValue(parameterValue, externalContext));
                    }
                    else
                    {
                        finalUrl.append(parameterValue);
                    }
                }
            }
        }
        return finalUrl.toString();
    }

    /**
     * Encodes the given value using URLEncoder.encode() with the charset returned
     * from ExternalContext.getResponseCharacterEncoding().
     * This is exactly how the ExternalContext impl encodes URL parameter values.
     *
     * @param value           value which should be encoded
     * @param externalContext current external-context
     * @return encoded value
     */
    public static String encodeURLParameterValue(String value, ExternalContext externalContext)
    {
        // copied from MyFaces ServletExternalContextImpl.encodeURL()
        try
        {
            return URLEncoder.encode(value, externalContext.getResponseCharacterEncoding());
        }
        catch (UnsupportedEncodingException e)
        {
            throw new UnsupportedOperationException("Encoding type="
                    + externalContext.getResponseCharacterEncoding() + " not supported", e);
        }
    }

    public static ViewConfigResolver getViewConfigResolver()
    {
        return BeanProvider.getContextualReference(ViewConfigResolver.class);
    }

    public static void saveFacesMessages(ExternalContext externalContext)
    {
        JsfModuleConfig jsfModuleConfig = BeanProvider.getContextualReference(JsfModuleConfig.class);

        if (!jsfModuleConfig.isAlwaysKeepMessages())
        {
            return;
        }

        try
        {
            WindowMetaData windowMetaData = BeanProvider.getContextualReference(WindowMetaData.class);

            Map<String, Object> requestMap = externalContext.getRequestMap();

            @SuppressWarnings({ "unchecked" })
            List<FacesMessageEntry> facesMessageEntryList =
                    (List<FacesMessageEntry>)requestMap.get(FacesMessageEntry.class.getName());

            if (facesMessageEntryList == null)
            {
                facesMessageEntryList = new CopyOnWriteArrayList<FacesMessageEntry>();
            }
            windowMetaData.setFacesMessageEntryList(facesMessageEntryList);
        }
        catch (ContextNotActiveException e)
        {
            //TODO log it in case of project-stage development
            //we can't handle it correctly -> delegate to the jsf-api (which has some restrictions esp. before v2.2)
            FacesContext.getCurrentInstance().getExternalContext().getFlash().setKeepMessages(true);
        }
    }

    public static void tryToRestoreMessages(FacesContext facesContext)
    {
        JsfModuleConfig jsfModuleConfig = BeanProvider.getContextualReference(JsfModuleConfig.class);

        if (!jsfModuleConfig.isAlwaysKeepMessages())
        {
            return;
        }

        try
        {
            WindowMetaData windowMetaData = BeanProvider.getContextualReference(WindowMetaData.class);

            @SuppressWarnings({ "unchecked" })
            List<FacesMessageEntry> facesMessageEntryList = windowMetaData.getFacesMessageEntryList();

            if (facesMessageEntryList != null)
            {
                for (FacesMessageEntry facesMessageEntry : facesMessageEntryList)
                {
                    facesContext.addMessage(facesMessageEntry.getComponentId(), facesMessageEntry.getFacesMessage());
                }
                facesMessageEntryList.clear();
            }
        }
        catch (ContextNotActiveException e)
        {
            //TODO discuss how we handle it
        }
    }
}
