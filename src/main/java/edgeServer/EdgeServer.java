/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package edgeServer;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.logging.Logger;
import java.lang.Runtime;
import java.lang.Process;
import java.lang.Thread;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.util.concurrent.TimeUnit;
import edgeOffloading.OffloadingGrpc;
import edgeOffloading.OffloadingOuterClass.OffloadingRequest;
import edgeOffloading.OffloadingOuterClass.OffloadingReply;

public class EdgeServer {
	private static final Logger logger = Logger.getLogger(EdgeServer.class.getName());
	private static final Map<String, Integer> IMAGEPOP = new HashMap<>();  // appId, app hit times
	private static final Map<String, Map<String, Double>> RATEMAP = new HashMap<>(); // appId, machineId, rate
	private Server server;
	private Server dockerServer;
	private Server cleanDocker;
	private static String LOCALIP; // localhost's ip address
	private static int appCount = 0;
	private static Receiver receiver;

	private void start() throws IOException {
		LOCALIP = InetAddress.getLocalHost().toString().split("/")[1];
		/* The port on which the server should run */
		int port = 50051;
		server = ServerBuilder.forPort(port)
				.addService(new OffloadingImpl())
				.build()
				.start();
		dockerServer = ServerBuilder.forPort(60051)
				.addService(new PrepareDockerImpl())
				.build()
				.start();
		cleanDocker = ServerBuilder.forPort(60052)
				.addService(new CleanDockerImpl())
				.build()
				.start();

		logger.info("Server started, listening on " + port);
		logger.info("dockerServer started, listening on 60051");
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				// Use stderr here since the logger may have been reset by its JVM shutdown hook.
				System.err.println("*** shutting down gRPC server since JVM is shutting down");
				EdgeServer.this.stop();
				System.err.println("*** server shut down");
			}
		});
	}

	private void stop() {
		if (server != null) {
			server.shutdown();
		}
		if (dockerServer != null) {
			dockerServer.shutdown();
		}
	}

	/**
	 * Await termination on the main thread since the grpc library uses daemon threads.
	 */
	private void blockUntilShutdown() throws InterruptedException {
		if (server != null) {
			server.awaitTermination();
		}
		if (dockerServer != null) {
			dockerServer.awaitTermination();
		}
	}

	/**
	 * Main launches the server from the command line.
	 */
	public static void main(String[] args) throws IOException, InterruptedException {
		final EdgeServer server = new EdgeServer();
		receiver = new Receiver();
		Thread receiverThread = new Thread(receiver);
		receiverThread.start();
		server.start();
		server.blockUntilShutdown();
	}

	static class CleanDockerImpl extends OffloadingGrpc.OffloadingImplBase {
		@Override
		public void startService(OffloadingRequest req, StreamObserver<OffloadingReply> responseObserver) {
			String containerID = req.getMessage();
			Runtime rt = Runtime.getRuntime();
			try {
				String command = "docker stop " + containerID;
				Process pr = rt.exec(command);
				System.out.println("stop the container " + containerID);
				Thread.sleep(10000);
				command = "docker rm " + containerID;
				pr = rt.exec(command);

				long time = System.currentTimeMillis();
				System.out.println("RuiLog : " + time + " : " + -1 + " : " + -1);
				System.out.println("remove the container " + containerID);

				BufferedReader in = new BufferedReader(new InputStreamReader(pr.getInputStream()));
				String inputLine;
				while((inputLine = in.readLine()) != null) {
					System.out.println(inputLine);
				}
				in.close();

			} catch (Exception e) {
				Thread.currentThread().interrupt();
			}
			OffloadingReply reply = OffloadingReply.newBuilder()
					.setMessage("successfully stop the " + containerID)
					.build();
			responseObserver.onNext(reply);
			responseObserver.onCompleted();
		}
	}

	static class PrepareDockerImpl extends OffloadingGrpc.OffloadingImplBase {

		@Override
		public void startService(OffloadingRequest req, StreamObserver<OffloadingReply> responseObserver) {
			int appPort = Integer.parseInt(req.getMessage().split(":")[0]);
			String reqMessage = req.getMessage().split(":")[1];
			System.out.println("[Rui] appPortt: " + appPort + ", reqMessage: " + reqMessage);
			EdgeServer s = new EdgeServer();
			OffloadingReply reply = OffloadingReply.newBuilder()
					.setMessage("well prepared")
					.build();
			Thread t = s.new offloadThread(reqMessage, appPort);
			long timeStart = System.currentTimeMillis();
			t.start();
			while(!containerReady(reqMessage.split("/")[1])) {
				try {
					TimeUnit.MILLISECONDS.sleep(100);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			long timeEnd = System.currentTimeMillis();
			System.out.println("Docker image downloading time: " + (timeEnd - timeStart));
			responseObserver.onNext(reply);
			responseObserver.onCompleted();
		}

		private boolean containerReady(String name) {
			Runtime rt = Runtime.getRuntime();
			try {
				// Execute the command
				String command = "docker container inspect -f {{.State.Running}} " + name;
				Process pr = rt.exec(command);
				// Get the input steam and read from it
				BufferedReader in = new BufferedReader(new InputStreamReader(pr.getInputStream()));
				String inputLine;
				while((inputLine = in.readLine()) != null) {
				  System.out.println(inputLine);
					/*
				  if(debug.equals("true")) {
            System.out.println(name + " is ready to serve!");
            return true;
          }
          */
				}
        in.close();
        return true;
			} catch (IOException e) {
				return false;
			}
		}
	}

	static class OffloadingImpl extends OffloadingGrpc.OffloadingImplBase {
		@Override
		public void startService(OffloadingRequest req, StreamObserver<OffloadingReply> responseObserver) {
			String appType = req.getMessage();
			String destinationIP = null;
			try {
				destinationIP = receiver.getAppDest(appType);
			} catch (Exception e) {
				new Exception().printStackTrace();
			}
			//System.out.println("destinationIP: " + destinationIP);
			OffloadingReply reply = OffloadingReply.newBuilder()
					.setMessage(destinationIP)
					.build();
			responseObserver.onNext(reply);
			responseObserver.onCompleted();
		}
	}

	public class offloadThread extends Thread {
		String dockerName;
		int dockerPort;

		public offloadThread(String dockerName, int dockerPort) {
			this.dockerName = dockerName;
			this.dockerPort = dockerPort;
		}

		public void run() {
			Runtime rt = Runtime.getRuntime();
			try {
				String address = Integer.toString(dockerPort) + ":" + Integer.toString(50052);
				long timeStart = System.currentTimeMillis();
				String command = "docker run -p " + address + " --name " + dockerName.split("/")[1] + Integer.toString(dockerPort) + "  " + dockerName;
				//System.out.println("[DEBUG] command: " + command);
				Process pr = rt.exec(command);
				long timeEnd = System.currentTimeMillis();
				System.out.println("Docker run overhead: " + (timeEnd - timeStart));
				System.out.println("Input end");
				System.out.println("start the container");
			} catch (IOException e) {
				new Exception().printStackTrace();
			}
		}
	}
}
