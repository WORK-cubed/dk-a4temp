package no.ntnu.datakomm.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.LinkedList;
import java.util.List;

public class TCPClient {
    private final List<ChatListener> listeners = new LinkedList<>();
    private PrintWriter toServer;
    private BufferedReader fromServer;
    private Socket connection;
    // Hint: if you want to store a message for the last error, store it here
    private String lastError = null;

    /**
     * Connect to a chat server.
     *
     * @param host host name or IP address of the chat server
     * @param port TCP port of the chat server
     * @return True on success, false otherwise
     */
    public boolean connect(String host, int port) {
        if (host == null || port < 0) {
            lastError = "Connection: Invalid arguments for connection";
            throw new IllegalArgumentException("Invalid arguments for connection");
        }

        boolean success = false;

        try {
            connection = new Socket(host, port);

            toServer = new PrintWriter(connection.getOutputStream(), true);
            fromServer = new BufferedReader(
              new InputStreamReader(connection.getInputStream())
            );

            success = true;
        } catch (IOException e) {
            lastError = "Couldn't connect to server";
        }

        return success;
    }

    /**
     * Close the socket. This method must be synchronized, because several
     * threads may try to call it. For example: When "Disconnect" button is
     * pressed in the GUI thread, the connection will get closed. Meanwhile, the
     * background thread trying to read server's response will get error in the
     * input stream and may try to call this method when the socket is already
     * in the process of being closed. with "synchronized" keyword we make sure
     * that no two threads call this method in parallel.
     */
    public synchronized void disconnect() {
        if (isConnectionActive()) {
            try {
                connection.close();
                connection = null;
                onDisconnect();
            } catch (IOException e) {
                lastError = "Failed to close connection with the server";
                e.printStackTrace();
            }
        }
    }

    /**
     * @return true if the connection is active (opened), false if not.
     */
    public boolean isConnectionActive() {
        return connection != null;
    }

    /**
     * Send a command to server.
     *
     * @param cmd A command. It should include the command word and optional attributes,
     *            according to the protocol.
     * @return true on success, false otherwise
     */
    private boolean sendCommand(String cmd) {
        if (cmd == null) {
            lastError = "Command may not be null";
            throw new IllegalArgumentException("Command may not be null");
        }
        boolean success = false;
        if (isConnectionActive()) {
            if (!cmd.isEmpty() && !cmd.trim().equals("")) {
                toServer.println(cmd);
                success = true;
            }
        }
        return success;
    }

    /**
     * Send a public message to all the recipients.
     *
     * @param message Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPublicMessage(String message) {
        if (message == null) {
            lastError = "Message may not be null";
            throw new IllegalArgumentException("Message may not be null");
        }
        StringBuilder stringBuilder = new StringBuilder(message);
        stringBuilder.insert(0, "msg ");

        return sendCommand(stringBuilder.toString());
    }

    /**
     * Send a login request to the chat server.
     *
     * @param username Username to use
     */
    public void tryLogin(String username) {
        if (username == null) {
            lastError = "Username is invalid";
            throw new IllegalArgumentException("Username cannot be null");
        }
        if (username.isEmpty() || username.trim().equals("")) {
            lastError = "Username cannot be empty or spaces";
            throw new IllegalArgumentException("Username cannot be empty or spaces");
        }

        sendCommand("login " + username);
    }

    /**
     * Send a request for latest user list to the server. To get the new users,
     * clear your current user list and use events in the listener.
     */
    public void refreshUserList() {
        sendCommand("users");
    }

    /**
     * Send a private message to a single recipient.
     *
     * @param recipient username of the chat user who should receive the message
     * @param message   Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPrivateMessage(String recipient, String message) {
        boolean success = false;
        if (isConnectionActive()) {
            success = sendCommand(String.format("privmsg %s %s", recipient, message));
            if (!success) {
                lastError = "Couldn't send private message";
            }
        }
        return success;
    }


    /**
     * Send a request for the list of commands that server supports.
     */
    public void askSupportedCommands() {
        this.sendCommand("help");
    }


    /**
     * Wait for chat server's response
     *
     * @return one line of text (one command) received from the server
     */
    private String waitServerResponse() {
        String response = "";
        try {
            response = fromServer.readLine();
        } catch (IOException e) {
            disconnect();

            lastError = "Server closed connection" + e.getMessage();
        }

        return response;
    }

    /**
     * Get the last error message
     *
     * @return Error message or "" if there has been no error
     */
    public String getLastError() {
        if (lastError != null) {
            return lastError;
        } else {
            return "";
        }
    }

    /**
     * Start listening for incoming commands from the server in a new CPU thread.
     */
    public void startListenThread() {
        // Call parseIncomingCommands() in the new thread.
        Thread t = new Thread(this::parseIncomingCommands);
        t.start();
    }

    /**
     * Read incoming messages one by one, generate events for the listeners. A loop that runs until
     * the connection is closed.
     */
    private void parseIncomingCommands() {
        while (isConnectionActive()) {
            String response = waitServerResponse();
            String command = response.split(" ")[0];
            int offset = command.length() + 1;
            switch (command) {
                case "loginok":
                    onLoginResult(true, "Successfully logged in");
                    break;
                case "loginerr":
                    onLoginResult(false, response.substring(offset));
                    break;
                case "users":
                    String[] users = response.substring(offset).split(" ");
                    onUsersList(users);
                    break;
                case "msg":
                case "privmsg":
                    boolean priv = command.equals("privmsg");
                    String sender = response.split(" ")[1];
                    String content = response.substring(offset + sender.length() + 1);
                    onMsgReceived(priv, sender, content);
                    break;
                case "msgerr":
                    onMsgError(response.substring(offset));
                    break;
                case "cmderr":
                    onCmdError(response.substring(offset));
                    break;
                case "supported":
                    onSupported(response.substring(offset).split(" "));
                    break;
                default:
                    //Handle unknown commands
                    break;
            }
        }
    }

    /**
     * Register a new listener for events (login result, incoming message, etc)
     *
     * @param listener
     */
    public void addListener(ChatListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Unregister an event listener
     *
     * @param listener
     */
    public void removeListener(ChatListener listener) {
        listeners.remove(listener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////
    // The following methods are all event-notificators - notify all the listeners about a specific
    // event.
    // By "event" here we mean "information received from the chat server".
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Notify listeners that login operation is complete (either with success or
     * failure)
     *
     * @param success When true, login successful. When false, it failed
     * @param errMsg  Error message if any
     */
    private void onLoginResult(boolean success, String errMsg) {
        for (ChatListener l : listeners) {
            l.onLoginResult(success, errMsg);
        }
    }

    /**
     * Notify listeners that socket was closed by the remote end (server or
     * Internet error)
     */
    private void onDisconnect() {
        for (ChatListener listener : listeners) {
            listener.onDisconnect();
        }
    }

    /**
     * Notify listeners that server sent us a list of currently connected users
     *
     * @param users List with usernames
     */
    private void onUsersList(String[] users) {
        for (ChatListener l : listeners) {
            l.onUserList(users);
        }
    }

    /**
     * Notify listeners that a message is received from the server
     *
     * @param priv   When true, this is a private message
     * @param sender Username of the sender
     * @param text   Message text
     */
    private void onMsgReceived(boolean priv, String sender, String text) {
        TextMessage textMessage = new TextMessage(sender, priv, text);
        for (ChatListener listener : listeners) {
            listener.onMessageReceived(textMessage);
        }
    }

    /**
     * Notify listeners that our message was not delivered
     *
     * @param errMsg Error description returned by the server
     */
    private void onMsgError(String errMsg) {
        for (ChatListener listener : listeners) {
            listener.onMessageError(errMsg);
        }
    }

    /**
     * Notify listeners that command was not understood by the server.
     *
     * @param errMsg Error message
     */
    private void onCmdError(String errMsg) {
        for (ChatListener listener : listeners) {
            listener.onCommandError(errMsg);
        }
    }

    /**
     * Notify listeners that a help response (supported commands) was received
     * from the server
     *
     * @param commands Commands supported by the server
     */
    private void onSupported(String[] commands) {
        for (ChatListener l: listeners) {
            l.onSupportedCommands(commands);
        }
    }
}
