package com.farmaprom;

import com.dtolabs.rundeck.plugins.step.PluginStepContext;
import com.farmaprom.helpers.TaskIdGeneratorHelper;
import com.farmaprom.logger.LoggerWrapper;
import org.apache.mesos.Protos;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class DockerScheduler implements Scheduler {

    private final String imageName;
    private final int desiredInstances;
    private final Double cpu;
    private final Double memory;
    private final Protos.CommandInfo.Builder commandInfoBuilder;
    private final List<Protos.Volume> volumes;
    private final PluginStepContext context;
    private final boolean forcePullImage;
    private final ConstraintsChecker constraints;

    private final LoggerWrapper loggerWrapper;

    private final List<String> pendingInstances = new ArrayList<>();

    private final List<String> runningInstances = new ArrayList<>();

    private final AtomicInteger taskIDGenerator = new AtomicInteger();

    DockerScheduler(
            LoggerWrapper loggerWrapper,
            int desiredInstances,
            Protos.CommandInfo.Builder commandInfoBuilder,
            List<Protos.Volume> volumes,
            final Map<String, Object> configuration,
            PluginStepContext context

    ) {
        this.loggerWrapper = loggerWrapper;
        this.desiredInstances = desiredInstances;
        this.commandInfoBuilder = commandInfoBuilder;
        this.volumes = volumes;
        this.context = context;

        this.imageName = configuration.get("docker_image").toString();
        this.cpu = Double.parseDouble(configuration.get("docker_cpus").toString());
        this.memory = Double.parseDouble(configuration.get("docker_memory").toString());
        this.forcePullImage = Boolean.parseBoolean(configuration.get("docker_force_pull").toString());
        this.constraints = new ConstraintsChecker(configuration.get("mesos_constraints").toString());
    }

    @Override
    public void registered(SchedulerDriver schedulerDriver, Protos.FrameworkID frameworkID, Protos.MasterInfo masterInfo) {
        loggerWrapper.info("Registered master=" + masterInfo.getIp() + ":" + masterInfo.getPort() +", framework=" + frameworkID);

        loggerWrapper.masterInfo = masterInfo;
        loggerWrapper.frameworkID = frameworkID;
    }

    @Override
    public void reregistered(SchedulerDriver schedulerDriver, Protos.MasterInfo masterInfo) {
        loggerWrapper.info("Re-registered");

        loggerWrapper.masterInfo = masterInfo;
    }

    @Override
    public void resourceOffers(SchedulerDriver schedulerDriver, List<Protos.Offer> offers) {

        loggerWrapper.info("Resource offers with " + offers.size() + " offers" );

        for (Protos.Offer offer : offers) {
            if (!constraints.constraintsAllow(offer)) {
                continue;
            }
            List<Protos.TaskInfo> tasks = new ArrayList<>();
            if (runningInstances.size() + pendingInstances.size() < desiredInstances) {

                // generate a unique task ID
                Protos.TaskID taskId = Protos.TaskID.newBuilder()
                        .setValue(TaskIdGeneratorHelper.getTaskId(context)).build();

                loggerWrapper.info("Launching task " + taskId.getValue());
                pendingInstances.add(taskId.getValue());

                // docker image info
                Protos.ContainerInfo.DockerInfo.Builder dockerInfoBuilder = Protos.ContainerInfo.DockerInfo.newBuilder();
                dockerInfoBuilder.setImage(imageName);
                dockerInfoBuilder.setNetwork(Protos.ContainerInfo.DockerInfo.Network.BRIDGE);
                dockerInfoBuilder.setForcePullImage(forcePullImage);

                // container info
                Protos.ContainerInfo.Builder containerInfoBuilder = Protos.ContainerInfo.newBuilder();
                containerInfoBuilder.setType(Protos.ContainerInfo.Type.DOCKER);
                containerInfoBuilder.setDocker(dockerInfoBuilder.build());
                if (!volumes.isEmpty()) {
                    containerInfoBuilder.addAllVolumes(volumes);
                }

                // create task to run
                Protos.TaskInfo task = Protos.TaskInfo.newBuilder()
                        .setName("task " + taskId.getValue())
                        .setTaskId(taskId)
                        .setSlaveId(offer.getSlaveId())
                        .addResources(Protos.Resource.newBuilder()
                                .setName("cpus")
                                .setType(Protos.Value.Type.SCALAR)
                                .setScalar(Protos.Value.Scalar.newBuilder().setValue(cpu)))
                        .addResources(Protos.Resource.newBuilder()
                                .setName("mem")
                                .setType(Protos.Value.Type.SCALAR)
                                .setScalar(Protos.Value.Scalar.newBuilder().setValue(memory)))
                        .setContainer(containerInfoBuilder)
                        .setCommand(commandInfoBuilder)
                        .build();

                tasks.add(task);

                loggerWrapper.task = task;
            }
            Protos.Filters filters = Protos.Filters.newBuilder().setRefuseSeconds(1).build();
            schedulerDriver.launchTasks(offer.getId(), tasks, filters);

        }
    }

    @Override
    public void offerRescinded(SchedulerDriver schedulerDriver, Protos.OfferID offerID) {
        loggerWrapper.info("Offer rescinded");
    }

    @Override
    public void statusUpdate(SchedulerDriver driver, Protos.TaskStatus taskStatus) {

        final String taskId = taskStatus.getTaskId().getValue();

        loggerWrapper.info("Status update task " + taskId + "  is in state " + taskStatus.getState());

        switch (taskStatus.getState()) {
            case TASK_RUNNING:
                pendingInstances.remove(taskId);
                runningInstances.add(taskId);
                break;
            case TASK_FAILED:
            case TASK_LOST:
            case TASK_KILLED:
                pendingInstances.remove(taskId);
                runningInstances.remove(taskId);

                driver.stop(false);
                break;
            case TASK_FINISHED:
                pendingInstances.remove(taskId);
                runningInstances.remove(taskId);

                driver.stop(false);
                break;
        }

        loggerWrapper.taskStatus = taskStatus;

        loggerWrapper.info(
                "Number of instances: pending=" + pendingInstances.size() + ", running=" + runningInstances.size()
        );
    }

    @Override
    public void frameworkMessage(SchedulerDriver schedulerDriver, Protos.ExecutorID executorID, Protos.SlaveID slaveID, byte[] bytes) {
        loggerWrapper.info("Framework message");
    }

    @Override
    public void disconnected(SchedulerDriver schedulerDriver) {
        loggerWrapper.info("Disconnected");
    }

    @Override
    public void slaveLost(SchedulerDriver schedulerDriver, Protos.SlaveID slaveID) {
        loggerWrapper.info("Slave lost");
    }

    @Override
    public void executorLost(SchedulerDriver schedulerDriver, Protos.ExecutorID executorID, Protos.SlaveID slaveID, int i) {
        loggerWrapper.info("Executor lost");
    }

    @Override
    public void error(SchedulerDriver schedulerDriver, String s) {
        loggerWrapper.info("Error: " + s);
    }
}


