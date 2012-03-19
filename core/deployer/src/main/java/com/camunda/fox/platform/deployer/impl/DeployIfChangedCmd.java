/**
 * Copyright (C) 2011, 2012 camunda services GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.camunda.fox.platform.deployer.impl;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilderFactory;

import org.activiti.engine.ActivitiException;
import org.activiti.engine.impl.DeploymentQueryImpl;
import org.activiti.engine.impl.ProcessDefinitionQueryImpl;
import org.activiti.engine.impl.interceptor.Command;
import org.activiti.engine.impl.interceptor.CommandContext;
import org.activiti.engine.impl.persistence.entity.DeploymentEntity;
import org.activiti.engine.impl.persistence.entity.ResourceEntity;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.ProcessDefinition;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Command creating a deployment if at least one process has changed
 *
 * @author Daniel Meyer
 * @author nico.rehwaldt@camunda.com
 */
public class DeployIfChangedCmd implements Command<String>, Serializable {

  private static final long serialVersionUID = 1L;
  private static Logger log = Logger.getLogger(DeployIfChangedCmd.class.getName());
  
  protected Map<String, byte[]> resources;
  protected ClassLoader classloader;
  protected final String name;
  protected transient CommandContext commandContext;
  protected final HashSet<String> activeDeployments;

  public DeployIfChangedCmd(String name, Map<String, byte[]> resources, ClassLoader classloader, HashSet<String> activeDeployments) {
    this.resources = resources;
    this.classloader = classloader;
    this.name = name;
    this.activeDeployments = activeDeployments;
  }

  public String execute(CommandContext commandContext) {
    this.commandContext = commandContext;

    String deploymentId = null;
    try {
      if (name == null) {
        throw new ActivitiException("Deployment 'name' cannot be null.");
      }
      if (resources.isEmpty()) {
        throw new ActivitiException("Deployment must contain at least one resource.");
      }

      deploymentId = tryResume();
      if (deploymentId != null) {
        log.info("No changes in  '" + name + "', loading exisiting process engine deployment with id " + deploymentId);
      } else {
        deploymentId = createNewDeployment();
        log.info("Created new process engine deployment with id " + deploymentId);
      }

      return deploymentId;

    } catch (Exception e) {
      throw new ActivitiException("Could not deploy '" + name + "': " + e.getMessage(), e);
    }
  }

  protected String createNewDeployment() {
    // create a new deployment
    DeploymentEntity deployment = new DeploymentEntity();
    deployment.setName(name);
    deployment.setDeploymentTime(new Date());
    deployment.setNew(true);

    for (Entry<String, byte[]> resourceEntry : resources.entrySet()) {
      ResourceEntity resource = new ResourceEntity();
      resource.setName(resourceEntry.getKey());
      resource.setBytes(resourceEntry.getValue());
      deployment.addResource(resource);
    }

    // perform the deployment
    commandContext.getDeploymentManager().insertDeployment(deployment);

    return deployment.getId();
  }

  /**
   * iterate all processes: check whether there already exists a process definition with the same key/id if true and
   * belongs to a deployment with a different name as the current deployment, throw exception if it is part of a
   * deployment with the same name: check whether it has changed if true deploy a new version ( i.e. the method returns
   * null )
   */
  protected String tryResume() {
    // try to retrieve a deployment with the same name:
    ProcessDefinition definition = null;
    String deploymentId = null;
    for (Entry<String, byte[]> resourceEntry : resources.entrySet()) {
      definition = getExistingProcessDefinition(resourceEntry.getKey(), resourceEntry.getValue());
      if (definition == null) {
        // 'resourceEntry' is a new process, not contained in the last deployment. 
        // We need to create a new deployment. 
        return null;
      } else if (definition.getDeploymentId() == null) {
        return null;
      } else {
        // candidate deployment:
        Deployment deployment = new DeploymentQueryImpl(commandContext).deploymentId(definition.getDeploymentId()).singleResult();

        if (deployment.getName().equals(name)) {
          // check whether the process has changed: 
          if (hasChanged(resourceEntry, deployment)) {
            log.info("The process '" + resourceEntry.getKey() + "' has changed, redeploying.");
            return null;
          } else {
            deploymentId = deployment.getId();
          }
        } else if (!activeDeployments.contains(deployment.getId())) {
          // a process with the same key was deployed in a processArchive with a 
          // different name, but that processArchive is not currently active (i.e. was undeployed).
          return null;
        } else {
          throw new ActivitiException("Cannot deploy process with id/key='" + definition.getKey()
            + "', a process with the same key is already deployed in process-archive '" + deployment.getName() + "' (deployment id='" + deployment.getId()
            + "') and " + deployment.getName() + " is active. Undeploy the existing process-archive or change the key of the process.");
        }
      }
    }
    return deploymentId;
  }

  /**
   * TODO: Check existing DeployCmd.deploymentsDiffer method (property duplicateFilterEnabled) which should be able to
   * to the same (on a byte array level but for all resources).
   *
   * Would be nice to have the same behaviour everywhere (especially in OSGI and Java EE)
   */
  protected boolean hasChanged(Entry<String, byte[]> resourceEntry, Deployment deployment) {
    byte[] existingProcessDefinitionBytes = commandContext.getDeploymentManager().findDeploymentById(deployment.getId()).getResource(resourceEntry.getKey()).getBytes();

    try {
      String existingProcessDefinitionXml = new String(existingProcessDefinitionBytes);
      String newProcessDefinitionXml = new String(resourceEntry.getValue(), "UTF8");
      return !xmlEquals(existingProcessDefinitionXml, newProcessDefinitionXml);
    } catch (Exception e) {
      log.log(Level.SEVERE, "Could not compare process definitions", e);
      return true;
    }
  }

  /**
   * (copied verbatim from Jbpm deployer)
   */
  protected boolean xmlEquals(String xml1, String xml2) {
    return removeIgnoredCharacters(xml1).equals(
      removeIgnoredCharacters(xml2));
  }

  /**
   * (copied verbatim from Jbpm deployer)
   *
   * replace characters in xml which should be ignored while comparing two processdefinition.xml's. Basically these are
   * new lines and tabs.
   *
   * White spaces are dangerous, maybe only a white space in a attribute name has changed!
   *
   * TODO: add more sophisticated way of comparing?
   */
  protected String removeIgnoredCharacters(String st) {
    return st.replaceAll("\n", "").replaceAll("\t", "");
  }

  protected ProcessDefinition getExistingProcessDefinition(String key, byte[] value) {
    Document doc = null;
    String processDefinitionKey = null;

    try {
      doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new ByteArrayInputStream(value));
      Element definitionElement = (Element) doc.getElementsByTagName("process").item(0);
      processDefinitionKey = definitionElement.getAttribute("id");
    } catch (Exception e) {
      log.warning("Could not retreive key from process definition. Creating a new deployment.");
      log.log(Level.FINE, "Exception", e);
    }

    if (processDefinitionKey != null) {
      // get latest definition for the same key: 
      return new ProcessDefinitionQueryImpl(commandContext).processDefinitionKey(processDefinitionKey).latestVersion().singleResult();
    } else {
      return null;
    }
  }
}