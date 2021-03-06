/* Generated by Streams Studio: October 8, 2014 6:07:19 AM EDT */
package com.ibm.streamsx.cdc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Date;

import org.apache.log4j.Logger;

import com.ibm.streams.operator.AbstractOperator;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.StreamingData.Punctuation;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.log4j.TraceLevel;
import com.ibm.streams.operator.model.Icons;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPortSet.WindowMode;
import com.ibm.streams.operator.model.InputPortSet.WindowPunctuationInputMode;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.OutputPortSet;
import com.ibm.streams.operator.model.OutputPortSet.WindowPunctuationOutputMode;
import com.ibm.streams.operator.model.OutputPorts;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;

/**
 * A source operator that does not receive any input streams and produces new
 * tuples. The method <code>produceTuples</code> is called to begin submitting
 * tuples.
 * <P>
 * For a source operator, the following event methods from the Operator
 * interface can be called:
 * </p>
 * <ul>
 * <li><code>initialize()</code> to perform operator initialization</li>
 * <li>allPortsReady() notification indicates the operator's ports are ready to
 * process and submit tuples</li>
 * <li>shutdown() to shutdown the operator. A shutdown request may occur at any
 * time, such as a request to stop a PE or cancel a job. Thus the shutdown() may
 * occur while the operator is processing tuples, punctuation marks, or even
 * during port ready notification.</li>
 * </ul>
 * <p>
 * With the exception of operator initialization, all the other events may occur
 * concurrently with each other, which lead to these methods being called
 * concurrently by different threads.
 * </p>
 */
@PrimitiveOperator(name = "CDCSource", namespace = "com.ibm.streamsx.cdc", description = "Java Operator CDCSource")
@InputPorts({ @InputPortSet(description = "Optional input ports", optional = true, controlPort = true, windowingMode = WindowMode.NonWindowed, windowPunctuationInputMode = WindowPunctuationInputMode.Oblivious) })
@OutputPorts({ @OutputPortSet(description = "Port that produces tuples", cardinality = 1, optional = false, windowPunctuationOutputMode = WindowPunctuationOutputMode.Generating) })
@Icons(location16 = "icons/CDCSource_16x16.png", location32 = "icons/CDCSource_32x32.png")
public class CDCSource extends AbstractOperator {

	private static Logger LOGGER = Logger.getLogger(CDCSource.class);
	
	protected OperatorContext operatorContext;

	protected ServerSocket serverSocket;
	protected Socket connectionSocket;
	protected BufferedReader fromClient;
	protected PrintWriter toClient;

	protected int port = 1324;

	@Parameter(description = "Port to listen", name = "port", optional = false)
	public void setPort(int port) {
		this.port = port;
	}

	public int getPort() {
		return port;
	}

	protected int maxCon = 1;

	@Parameter(description = "Maximum number of connection", name = "maxCon", optional = true)
	public void setMaxCon(int maxCon) {
		this.maxCon = maxCon;
	}

	public int getMaxCon() {
		return maxCon;
	}

	protected String metadataSeparator = "\u0000";

	@Parameter(description = "Field separator", name = "metadataSeparator", optional = true)
	public void setseparator(String metadataSeparator) {
		this.metadataSeparator = metadataSeparator;
	}

	public String getSeparator() {
		return metadataSeparator;
	}

	protected boolean hasInputPort;
	/**
	 * Thread for calling <code>produceTuples()</code> to produce tuples
	 */
	private Thread processThread;

	protected void WaitForClient() throws Exception {
		if (serverSocket == null)
			serverSocket = new ServerSocket(port);
		connectionSocket = serverSocket.accept();
		LOGGER.log(TraceLevel.TRACE,
				"New Client connected : " + connectionSocket.getInetAddress()
						+ ":" + connectionSocket.getPort());
		toClient = new PrintWriter(connectionSocket.getOutputStream(), true);
		fromClient = new BufferedReader(new InputStreamReader(
				connectionSocket.getInputStream()));
		toClient.println("i" + metadataSeparator
				+ Utility.ISO_DATEFORMAT.format(new Date()));
		toClient.flush();
	}

	/**
	 * Initialize this operator. Called once before any tuples are processed.
	 * 
	 * @param operatorContext
	 *            OperatorContext for this operator.
	 * @throws Exception
	 *             Operator failure, will cause the enclosing PE to terminate.
	 */
	@Override
	public synchronized void initialize(OperatorContext operatorContext)
			throws Exception {
		// Must call super.initialize(context) to correctly setup an operator.
		super.initialize(operatorContext);
		this.operatorContext = operatorContext;
		LOGGER.log(TraceLevel.TRACE, "Operator " + operatorContext.getName()
				+ " initializing in PE: " + operatorContext.getPE().getPEId()
				+ " in Job: " + operatorContext.getPE().getJobId());
		LOGGER.log(TraceLevel.TRACE, "Operator " + operatorContext.getName()
				+ " listening at port number " + port);
		hasInputPort = !operatorContext.getStreamingInputs().isEmpty();

		// Start listening on the specified port
		WaitForClient();
		/*
		 * Create the thread for producing tuples. The thread is created at
		 * initialize time but started. The thread will be started by
		 * allPortsReady().
		 */
		processThread = getOperatorContext().getThreadFactory().newThread(
				new Runnable() {
					@Override
					public void run() {
						try {
							produceTuples();
						} catch (Exception e) {
							LOGGER.log(TraceLevel.ERROR,
									"Operator error " + e.getMessage());
						}
					}

				});

		/*
		 * Set the thread not to be a daemon to ensure that the SPL runtime will
		 * wait for the thread to complete before determining the operator is
		 * complete.
		 */
		processThread.setDaemon(false);
	}

	/**
	 * Notification that initialization is complete and all input and output
	 * ports are connected and ready to receive and submit tuples.
	 * 
	 * @throws Exception
	 *             Operator failure, will cause the enclosing PE to terminate.
	 */
	@Override
	public synchronized void allPortsReady() throws Exception {
		OperatorContext context = getOperatorContext();
		LOGGER.log(TraceLevel.TRACE, "Operator " + context.getName()
				+ " all ports are ready in PE: " + context.getPE().getPEId()
				+ " in Job: " + context.getPE().getJobId());
		// Start a thread for producing tuples because operator
		// implementations must not block and must return control to the caller.
		processThread.start();
	}

	/**
	 * Submit new tuples to the output stream
	 * 
	 * @throws Exception
	 *             if an error occurs while submitting a tuple
	 */
	private void produceTuples() throws Exception {
		final StreamingOutput<OutputTuple> out = getOutput(0);
		StreamSchema outputSchema = operatorContext.getStreamingOutputs()
				.get(0).getStreamSchema();
		String outputTuple = "<";
		for (String attrName : outputSchema.getAttributeNames()) {
			if (!outputTuple.equals("<"))
				outputTuple += ", ";
			outputTuple += outputSchema.getAttribute(attrName).getType()
					.getLanguageType();
			outputTuple += " " + attrName;
		}
		outputTuple += ">";
		LOGGER.log(TraceLevel.TRACE, "Output tuple for CDCSource operator is "
				+ outputTuple);
		String messageReceived;
		while (true) {

			messageReceived = null;
			try {
				messageReceived = fromClient.readLine();
			} catch (IOException ignore) {
			}
			if (messageReceived == null) {
				connectionSocket.close();
				super.getOutput(0).punctuate(Punctuation.WINDOW_MARKER);
				WaitForClient();
				continue;
			}
			// char c=messageReceived.charAt(0);
			String[] messageContent = messageReceived.split(metadataSeparator);
			char recordType = messageContent[0].charAt(0);
			OutputTuple tuple = out.newTuple();
			tuple.setString("txTableName", messageContent[1]);
			tuple.setString("txTimestamp", messageContent[2]);
			tuple.setString("txId", "");
			tuple.setString("txEntryType", "");
			tuple.setString("txUser", "");
			tuple.setString("data", "");
			switch (recordType) {
			case 'd':// Data
				/*
				 * Tuple to be sent: rstring recordType, rstring tableName
				 * rstring txTimestamp rstring txId rstring entryType (I/U/D)
				 * rstring txUser rstring record itself
				 */
				LOGGER.log(TraceLevel.TRACE, "Data record received");
				tuple.setString("txId", messageContent[3]);
				tuple.setString("txEntryType", messageContent[4]);
				tuple.setString("txUser", messageContent[5]);
				tuple.setString("data", messageContent[6]);
				out.submit(tuple);
				break;
			case 'c':// Commit
				LOGGER.log(TraceLevel.TRACE, "Commit record received");
				out.punctuate(Punctuation.WINDOW_MARKER);
				break;
			case 'i':// Initialize
				LOGGER.log(TraceLevel.TRACE, "Initialization tuple received");
				out.submit(tuple);
				break;
			case 'f':// Final
				LOGGER.log(TraceLevel.TRACE, "Final tuple received");
				break;
			case 'h':// Handshake
				LOGGER.log(TraceLevel.TRACE, "Handshake received");
				if (!hasInputPort) {
					toClient.println(messageReceived + metadataSeparator
							+ Utility.ISO_DATEFORMAT.format(new Date()));
					toClient.flush();
				}
				// TODO: If there is an input port, wait until one of the
				// downstream operators has confirmed and only then send the
				// confirmation back to the CDC user exit to commit the
				// bookmkark
				break;

			default:
				LOGGER.log(TraceLevel.ERROR, "Invalid record received: "
						+ messageReceived);
				break;
			}
		}
	}

	/**
	 * @param inputStream
	 *            Port the tuple is arriving on.
	 * @param tuple
	 *            Object representing the incoming tuple.
	 * @throws Exception
	 *             Operator failure, will cause the enclosing PE to terminate.
	 */
	@Override
	public final void process(StreamingInput<Tuple> inputStream, Tuple tuple)
			throws Exception {
	}

	/**
	 * Shutdown this operator, which will interrupt the thread executing the
	 * <code>produceTuples()</code> method.
	 * 
	 * @throws Exception
	 *             Operator failure, will cause the enclosing PE to terminate.
	 */
	public synchronized void shutdown() throws Exception {
		if (processThread != null) {
			processThread.interrupt();
			processThread = null;
		}
		OperatorContext context = getOperatorContext();
		LOGGER.log(TraceLevel.TRACE, "Operator " + context.getName()
				+ " shutting down in PE: " + context.getPE().getPEId()
				+ " in Job: " + context.getPE().getJobId());
		// Close connections
		connectionSocket.close();
		serverSocket.close();
		// Must call super.shutdown()
		super.shutdown();
	}
}