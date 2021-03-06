package org.bitseal.controllers;

import org.bitseal.core.App;
import org.bitseal.network.NetworkHelper;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

/**
 * A Singleton class that provides a thread for downloading
 * new messages. 
 * 
 * @author Jonathan Coe
 */
public class MessageDownloadThread 
{
	/** Defines the minimum amount of time (in seconds) behind the network for which we will make a 'check for new msgs' server request */
	private static final int MINIMUM_TIME_BEHIND_NETWORK = 30;
	
	/** A key used to store the time of the last successful 'check for new msgs' server request */
	private static final String LAST_MSG_CHECK_TIME = "lastMsgCheckTime";
	
	private Thread downloadThread;
	
	private static final String TAG = "MESSAGE_DOWNLOAD_THREAD";
	
	private static class Holder 
    {
        static MessageDownloadThread INSTANCE = new MessageDownloadThread();
    }
	
	/**
	 * Returns a singleton instance of the MessageDownloadThread. This ensures that
	 * only one instance of the thread will ever be exist at once. 
	 */
    protected static MessageDownloadThread getInstance() 
    {
        return Holder.INSTANCE;
    }
    
    /**
     * Starts the thread for downloading new messages, in such a way that the 
     * thread will only be started if it is not already running. 
     */
    protected void startThread()
    {
    	if (downloadThread.getState() == Thread.State.NEW) // The thread has not been started yet
    	{
    		downloadThread.start();
    	}
    	else if (downloadThread.getState() == Thread.State.TERMINATED) // The thread has run to completion
    	{
    		setNewThreadInstance();	
    		MessageDownloadThread.getInstance().startThread();
    	}
    	else
    	{
    		Log.d(TAG, "MessageDownloadThread.startThread() was called, but the thread is already running.");
    	}
    }
    
    /**
     * Creates a new MessageDownloadThread instance and sets the
     * static INSTANCE variable to point to it. 
     */
    private void setNewThreadInstance()
    {
    	Holder.INSTANCE = new MessageDownloadThread();
    }
    
    private MessageDownloadThread()
    {
    	// Create a thread for downloading new messages
	    downloadThread = new Thread(new Runnable()
	    {
	    	@Override
	        public void run()
	        {
	            try
	            {
		        	Log.i(TAG, "Starting message download thread.");
		            
		        	SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(App.getContext());
		    		long lastMsgCheckTime = prefs.getLong(LAST_MSG_CHECK_TIME, 0);
		    		long currentTime = System.currentTimeMillis() / 1000;
		    		
		    		// Check whether we are significantly behind in checking for new msgs. If we are AND there is an internet connection available
		    	    // then we should keep downloading new msgs
		    		CheckForMessagesController controller = new CheckForMessagesController();
		            while (((currentTime - lastMsgCheckTime) > MINIMUM_TIME_BEHIND_NETWORK) && (NetworkHelper.checkInternetAvailability() == true))
		            {
		            	controller.checkServerForMessages();
		            	
			    		lastMsgCheckTime = prefs.getLong(LAST_MSG_CHECK_TIME, 0);
			    		currentTime = System.currentTimeMillis() / 1000;
			    		
			    		// Start the message processing thread
			    		MessageProcessingThread.getInstance().startThread();
		            }
		            
		            Log.i(TAG, "Finishing message download thread.");
	            }
	            catch (Exception e)
	            {
	            	Log.e(TAG, "While running MessageDownloadThread(), downloadThread.run() threw an Execption. \n" +
        					"The exception message was: " + e.getMessage());
	            }
	        }
	    });
    }
}