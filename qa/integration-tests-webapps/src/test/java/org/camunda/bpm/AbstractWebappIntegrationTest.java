/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm;

import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Logger;

import org.apache.http.impl.client.DefaultHttpClient;
import org.camunda.bpm.cycle.test.TestCycleRoundtripIT;
import org.camunda.bpm.cycle.util.IoUtil;
import org.junit.After;
import org.junit.Before;

import com.sun.jersey.api.client.config.ClientConfig;
import com.sun.jersey.api.json.JSONConfiguration;
import com.sun.jersey.client.apache4.ApacheHttpClient4;
import com.sun.jersey.client.apache4.config.DefaultApacheHttpClient4Config;

/**
 * <p>Abstract Base class for Webapp integration tests.</p>
 * 
 * <p>Provides infrastructure for connecting to the application under 
 * test and provides a HttpClient instance.</p>
 * 
 * @author Daniel Meyer
 * @author Roman Smirnov
 *
 */
public abstract class AbstractWebappIntegrationTest {
  
  private final static Logger LOGGER = Logger.getLogger(AbstractWebappIntegrationTest.class.getName());
  
  public static final String HOST_NAME = "localhost";
  public String httpPort;
  public String APP_BASE_PATH;
  
  public ApacheHttpClient4 client;
  public DefaultHttpClient defaultHttpClient;
    
  @Before
  public void createClient() throws Exception {
    Properties properties = new Properties();
    
    InputStream propertiesStream = null;
    try {
      propertiesStream = TestCycleRoundtripIT.class.getResourceAsStream("/testconfig.properties");
      properties.load(propertiesStream);
      httpPort = (String) properties.get("http.port");
    } finally {
      IoUtil.closeSilently(propertiesStream);
    }
    
    String applicationContextPath = getApplicationContextPath();
    APP_BASE_PATH = "http://" + HOST_NAME + ":"+httpPort+"/"+applicationContextPath;
    LOGGER.info("Connecting to application "+APP_BASE_PATH);
    
    ClientConfig clientConfig = new DefaultApacheHttpClient4Config();
    clientConfig.getFeatures().put(JSONConfiguration.FEATURE_POJO_MAPPING, Boolean.TRUE);
    client = ApacheHttpClient4.create(clientConfig);
    
    defaultHttpClient = (DefaultHttpClient) client.getClientHandler().getHttpClient();  
    
  }
  
  @After
  public void destroyClient() {
    client.destroy();
  }

  /** 
   * <p>subclasses must override this method ad provide the local context path of the application they are testing.</p>
   * 
   * <p>Example: <code>cycle/</code></p>
   */ 
  protected abstract String getApplicationContextPath();

}
