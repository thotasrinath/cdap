/*
 * Copyright © 2018 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.runtime.spi.provisioner.emr;

import co.cask.cdap.runtime.spi.SparkCompat;
import co.cask.cdap.runtime.spi.provisioner.Capabilities;
import co.cask.cdap.runtime.spi.provisioner.Cluster;
import co.cask.cdap.runtime.spi.provisioner.ClusterStatus;
import co.cask.cdap.runtime.spi.provisioner.Node;
import co.cask.cdap.runtime.spi.provisioner.PollingStrategies;
import co.cask.cdap.runtime.spi.provisioner.PollingStrategy;
import co.cask.cdap.runtime.spi.provisioner.ProgramRun;
import co.cask.cdap.runtime.spi.provisioner.Provisioner;
import co.cask.cdap.runtime.spi.provisioner.ProvisionerContext;
import co.cask.cdap.runtime.spi.provisioner.ProvisionerSpecification;
import co.cask.cdap.runtime.spi.ssh.SSHKeyPair;
import co.cask.cdap.runtime.spi.ssh.SSHSession;
import com.amazonaws.services.elasticmapreduce.model.ClusterSummary;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Provisions a cluster using Amazon EMR.
 */
public class ElasticMapReduceProvisioner implements Provisioner {

  private static final Logger LOG = LoggerFactory.getLogger(ElasticMapReduceProvisioner.class);
  private static final ProvisionerSpecification SPEC = new ProvisionerSpecification(
    "aws-emr", "Amazon EMR",
    "Amazon EMR provides a managed Hadoop framework that makes it easy, fast, and cost-effective to " +
            "process vast amounts of data across dynamically scalable Amazon EC2 instances.");
  private static final String CLUSTER_PREFIX = "cdap-";

  @Override
  public ProvisionerSpecification getSpec() {
    return SPEC;
  }

  @Override
  public void validateProperties(Map<String, String> properties) {
    EMRConf.fromProperties(properties);
  }

  @Override
  public Cluster createCluster(ProvisionerContext context) throws Exception {
    if (!SparkCompat.SPARK2_2_11.equals(context.getSparkCompat())) {
      throw new UnsupportedOperationException("EMR currently only supports " + SparkCompat.SPARK2_2_11);
    }
    // Generates and set the ssh key
    SSHKeyPair sshKeyPair = context.getSSHContext().generate("ec2-user"); // or 'hadoop'
    context.getSSHContext().setSSHKeyPair(sshKeyPair);

    EMRConf conf = EMRConf.fromProvisionerContext(context);
    String clusterName = getClusterName(context.getProgramRun());

    try (EMRClient client = EMRClient.fromConf(conf)) {
      // if it already exists, it means this is a retry. We can skip actually making the request
      Optional<ClusterSummary> existing = client.getUnterminatedClusterByName(clusterName);
      if (existing.isPresent()) {
        return client.getCluster(existing.get().getId()).get();
      }
      String clusterId = client.createCluster(clusterName);
      return new Cluster(clusterId, ClusterStatus.CREATING, Collections.emptyList(), Collections.emptyMap());
    }
  }

  @Override
  public ClusterStatus getClusterStatus(ProvisionerContext context, Cluster cluster) throws Exception {
    EMRConf conf = EMRConf.fromProvisionerContext(context);
    try (EMRClient client = EMRClient.fromConf(conf)) {
      return client.getClusterStatus(cluster.getName());
    }
  }

  @Override
  public Cluster getClusterDetail(ProvisionerContext context,
                                  Cluster cluster) throws Exception {
    EMRConf conf = EMRConf.fromProvisionerContext(context);
    try (EMRClient client = EMRClient.fromConf(conf)) {
      Optional<Cluster> existing = client.getCluster(cluster.getName());
      return existing.orElseGet(() -> new Cluster(cluster, ClusterStatus.NOT_EXISTS));
    }
  }

  @Override
  public void initializeCluster(ProvisionerContext context, Cluster cluster) throws Exception {
    // Start the ZK server
    try (SSHSession session = createSSHSession(context, getMasterExternalIp(cluster))) {
      LOG.debug("Starting zookeeper server.");
      String output = session.executeAndWait("sudo zookeeper-server start");
      LOG.debug("Zookeeper server started: {}", output);
    }
  }

  private SSHSession createSSHSession(ProvisionerContext provisionerContext, String host) throws IOException {
    try {
      return provisionerContext.getSSHContext().createSSHSession(host);
    } catch (IOException ioe) {
      if (Throwables.getRootCause(ioe) instanceof ConnectException) {
        throw new IOException(String.format(
                "Failed to connect to host %s. Ensure that the the provisioner property for \"Additional Master" +
                        " Security Group\" has been configured with an EC2 Security Group that has inbound rules" +
                        " allowing ssh on port 22.", host),
                ioe);
      }
      throw ioe;
    }
  }

  @Override
  public void deleteCluster(ProvisionerContext context, Cluster cluster) throws Exception {
    EMRConf conf = EMRConf.fromProvisionerContext(context);
    try (EMRClient client = EMRClient.fromConf(conf)) {
      client.deleteCluster(cluster.getName());
    }
  }

  @Override
  public PollingStrategy getPollingStrategy(ProvisionerContext context, Cluster cluster) {
    return PollingStrategies.fixedInterval(20, TimeUnit.SECONDS);
  }

  private String getMasterExternalIp(Cluster cluster) {
    Node masterNode = cluster.getNodes().stream()
      .filter(node -> Node.Type.MASTER == node.getType())
      .findFirst().orElseThrow(() -> new IllegalArgumentException("Cluster has no node of master type: " + cluster));

    String ip = masterNode.getIpAddress();
    if (ip == null) {
      throw new IllegalArgumentException(String.format("External IP is not defined for node '%s' in cluster %s",
                                                       masterNode.getId(), cluster));
    }
    return ip;
  }

  // Name must start with a lowercase letter followed by up to 51 lowercase letters,
  // numbers, or hyphens, and cannot end with a hyphen
  // We'll use app-runid, where app is truncated to fit, lowercased, and stripped of invalid characters
  @VisibleForTesting
  static String getClusterName(ProgramRun programRun) {
    String cleanedAppName = programRun.getApplication().replaceAll("[^A-Za-z0-9\\-]", "").toLowerCase();
    // 51 is max length, need to subtract the prefix and 1 extra for the '-' separating app name and run id
    int maxAppLength = 51 - CLUSTER_PREFIX.length() - 1 - programRun.getRun().length();
    if (cleanedAppName.length() > maxAppLength) {
      cleanedAppName = cleanedAppName.substring(0, maxAppLength);
    }
    return CLUSTER_PREFIX + cleanedAppName + "-" + programRun.getRun();
  }

  @Override
  public Capabilities getCapabilities() {
    return new Capabilities(Collections.unmodifiableSet(new HashSet<>(Arrays.asList("fileSet", "externalDataset"))));
  }
}
