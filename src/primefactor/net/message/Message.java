package primefactor.net.message;

import java.io.Serializable;

/**
 * Created by n0ne on 14/03/17.
 */
public abstract class Message implements Serializable {

	public static final String CONST_PROT_NEWLINE = "\n";
	public static final String CONST_PROT_SPACE = " ";

	public static class InvalidMessageException extends RuntimeException {

		public InvalidMessageException (String message) {
			super(message);
		}

	}

}
