package primefactor.net;

import primefactor.net.message.ClientToServerMessage;
import primefactor.net.message.ClientToServerMessage.FactorMessage;
import primefactor.net.message.ClientToUserMessage;
import primefactor.net.message.ServerToClientMessage;
import primefactor.net.message.UserToClientMessage;
import primefactor.util.BigMath;

import java.io.*;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.util.LinkedList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.*;

/**
 * MasterClient class for PrimeFactorsServer.
 * <p>
 * Your MasterClient class should take in Program arguments space-delimited
 * indicating which PrimeFactorsServers it will connect to.
 * ex. args of "localhost:4444 localhost:4445 localhost:4446"
 * will connect the primefactor to PrimeFactorsServers running on
 * localhost:4444, localhost:4445, localhost:4446
 * <p>
 * Your primefactor should take user input from standard input.  The appropriate input
 * that can be processed is a number.  If your input is not of the correct format,
 * you should ignore it and continue to the next one.
 * <p>
 * Your primefactor should distribute to each server the appropriate range of values
 * to look for prime primefactor through.
 */
public class MasterClient implements Closeable {

	public static final String CONST_ADDRESS_SEP = ":";
	public static final String CONST_USER_INPUT = "Unsigned integer to factor: ";
	public static final int CONST_DEFAULT_AWAIT_TIME_SECONDS = 10;

	private Socket connection;

	private Scanner userIn;
	private PrintStream userOut;

	public MasterClient (final String address) throws IOException {
		final String[] splitAddress = address.split(CONST_ADDRESS_SEP);

		connection = new Socket(splitAddress[0], Integer.valueOf(splitAddress[1]));
		userIn = new Scanner(System.in);
		userOut = System.out;
	}

	void writeSpawnMessage (final ClientToServerMessage.SpawnMessage message) throws IOException {
		final ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());

		out.writeObject(message);
	}

	ServerToClientMessage.SpawnMessage readSpawnMessage () throws IOException, ClassNotFoundException {
		return (ServerToClientMessage.SpawnMessage) new ObjectInputStream(connection.getInputStream()).readObject();
	}

	boolean writeUserFactoringResult (ClientToUserMessage message) {
		//This method previously contained what is now in the ClientToUserMessage.toString() method.
		//TO-DO Check the refactoring works well.
		return writeUser(message.toString());
	}

	public String readUserRaw () {
		if (userIn.hasNextLine()) {
			return userIn.nextLine();
		}

		return null;
	}

	public boolean writeUser (String message) {
		userOut.println(String.valueOf(message));

		return userOut.checkError();
	}

	/**
	 * @param args String array containing Program arguments.  Each String indicates a
	 *             PrimeFactorsServer location in the form "host:port"
	 *             If no program arguments are inputted, this Client will terminate.
	 */
	public static void main (String[] args) throws Exception {
		final MasterClient client;

		FactorMessage serverOutMessage;
		List<FactorMessage> factoringPartitions;
		ClientToServerMessage.SpawnMessage serverOutSpawnMessage;
		ServerToClientMessage.SpawnMessage serverInSpawnMessage;

		ClientToUserMessage userOutMessage;
		UserToClientMessage.FactorMessage userInMessage;

		Future[] workerClientsResults;
		ThreadPoolExecutor threadPool;

		if (args.length > 0) {
			client = new MasterClient(args[0]);

			do {
				client.writeUser(CONST_USER_INPUT);
				userInMessage = UserToClientMessage.FactorMessage.factorMessageFactory(client.readUserRaw());

				if (userInMessage != null) {
					serverOutMessage = new FactorMessage(
							userInMessage.getN(),
							FactorMessage.CONST_MIN_LOW_BOUND,
							BigMath.sqrt(userInMessage.getN())
					);
					factoringPartitions = serverOutMessage.partition();
					serverOutSpawnMessage = new ClientToServerMessage.SpawnMessage(factoringPartitions.size());
					userOutMessage = new ClientToUserMessage(userInMessage.getN());

					threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(factoringPartitions.size());
					workerClientsResults = new Future[factoringPartitions.size()];

					client.writeSpawnMessage(serverOutSpawnMessage);

					for (int server = 0; server < serverOutSpawnMessage.getServersNumber(); server++) {
						serverInSpawnMessage = client.readSpawnMessage();

						/*
						Now that we have a server to assign a partition to factor, we:
							* Invoke a new thread in which to handle the communication between that server and the client;
						 */
						workerClientsResults[server] = threadPool.submit(
								new PrimeFactorsClient(
										serverInSpawnMessage.getAddress(),
										serverInSpawnMessage.getPort(),
										factoringPartitions.get(server)
								)
						);
					}

					threadPool.awaitTermination(CONST_DEFAULT_AWAIT_TIME_SECONDS, TimeUnit.SECONDS);

					for (int result = 0; result < workerClientsResults.length; result++) {
						userOutMessage.addFactors(
								((PrimeFactorsClient.Result) workerClientsResults[result].get()).getFactors()
						);
					}

					client.writeUserFactoringResult(userOutMessage);
				}
			} while (userInMessage != null); //Until the user input is valid

			client.close();
		} else {
			System.err.format("%s: <server:port>\n", MasterClient.class.getSimpleName());
			System.exit(1);
		}
	}

	@Override
	public void close () throws IOException {
		connection.close();
	}

	static class PrimeFactorsClient implements Callable<PrimeFactorsClient.Result> {

		private InetAddress address;
		private int port;
		private ClientToServerMessage.FactorMessage factorMessage;

		public PrimeFactorsClient (InetAddress address, int port, FactorMessage factorMessage) {
			this.address = address;
			this.port = port;
			this.factorMessage = factorMessage;
		}

		@Override
		public Result call () throws Exception {
			final Result result = new Result(factorMessage.getN(), factorMessage.getLowBound(), factorMessage.getHighBound());
			ServerToClientMessage serverInMessage;

			final Socket connection = new Socket(address, port);
			ObjectOutputStream out = new ObjectOutputStream(connection.getOutputStream());
			ObjectInputStream in = new ObjectInputStream(connection.getInputStream());

			out.writeObject(factorMessage);

			do {
				serverInMessage = (ServerToClientMessage) in.readObject();

				if (serverInMessage instanceof ServerToClientMessage.FoundMessage) {
					result.addFactor(((ServerToClientMessage.FoundMessage) serverInMessage).getFactor());
				} else if (serverInMessage instanceof ServerToClientMessage.InvalidMessage) {
					//TO-DO What should one do here when receiving an InvalidMessage?
				}
			} while (serverInMessage instanceof ServerToClientMessage.FoundMessage);

			out.close();
			in.close();
			connection.close();

			return result;
		}

		static class Result {

			private BigInteger n;
			private BigInteger low, high;
			private List<BigInteger> factors;

			public Result (BigInteger n, BigInteger low, BigInteger high) {
				this.n = n;
				this.low = low;
				this.high = high;
				this.factors = new LinkedList<>();
			}

			public BigInteger getN () {
				return n;
			}

			public BigInteger getLowBound () {
				return low;
			}

			public BigInteger getHighBound () {
				return high;
			}

			public List<BigInteger> getFactors () {
				return factors;
			}

			/**
			 *
			 * @param factor factor to add to the list of factors of n.
			 * @return return value of {@link List#add}
			 */
			public boolean addFactor (final BigInteger factor) {
				return factors.add(factor);
			}

		}

	}

}
