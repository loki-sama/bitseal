package org.bitseal.services;
import info.guardianproject.cacheword.CacheWordHandler;
import info.guardianproject.cacheword.ICacheWordSubscriber;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;

import org.bitseal.R;
import org.bitseal.controllers.TaskController;
import org.bitseal.core.App;
import org.bitseal.core.ObjectProcessor;
import org.bitseal.core.QueueRecordProcessor;
import org.bitseal.data.Address;
import org.bitseal.data.Message;
import org.bitseal.data.Payload;
import org.bitseal.data.Pubkey;
import org.bitseal.data.QueueRecord;
import org.bitseal.database.AddressProvider;
import org.bitseal.database.DatabaseContentProvider;
import org.bitseal.database.MessageProvider;
import org.bitseal.database.PayloadProvider;
import org.bitseal.database.PayloadsTable;
import org.bitseal.database.PubkeyProvider;
import org.bitseal.database.PubkeysTable;
import org.bitseal.database.QueueRecordProvider;
import org.bitseal.database.QueueRecordsTable;
import org.bitseal.network.NetworkHelper;
import org.bitseal.util.ByteUtils;
import org.bitseal.util.TimeUtils;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import com.commonsware.cwac.wakeful.WakefulIntentService;

/**
 * This class handles all the long-running processing required
 * by the application. 
 * 
 * @author Jonathan Coe
 */
public class BackgroundService extends WakefulIntentService  implements ICacheWordSubscriber
{
	/**
	 * This constant determines whether or not the app will do
	 * proof of work for pubkeys and messages that it creates. 
	 * If not, it will expect servers to do the proof of work. 
	 */
	public static final boolean DO_POW = true;
	
	/**
	 * The 'time to live' value (in seconds) that we use in the 'first attempt' to create
	 * and send some types of objects (such as msgs sent by us). This is done
	 * because in protocol version 3, objects with a lower time to live require less
	 * proof of work for the network to relay them. <br><br>
	 * 
	 * Therefore in some situations it is advantageous to use a low time to live
	 * when creating and sending an object, for example when you are sending a
	 * msg and the recipient is online and therefore able to receive and acknowledge
	 * it immediately. 
	 */
	public static final long FIRST_ATTEMPT_TTL = 3600; // Currently set to 1 hour
	
	/**
	 * The 'time to live' value (in seconds) that we use in all attempts after the
	 * first attempt  to create and send some types of objects (such as msgs sent
	 * by us). <br><br>
	 * 
	 * If we create and send out an object using a low time to live and the first attempt is 
	 * not successful (e.g. we do not receive an acknowledgement for a sent msg) then we can 
	 * re-create and re-send the object with a longer time to live. That time to live is 
	 * determined by this constant.  
	 */
	public static final long SUBSEQUENT_ATTEMPTS_TTL = 86400; // Currently set to 1 day
		
	/**
	 * The minimum amount of time (in seconds) which a Bitmessage Object we are going to send out
	 * must have until its expiration time. If there is less than this much time between now and
	 * the Object's expiration time, we will discard it and create a new Object with an updated
	 * time to live and new proof of work. 
	 */
	public static final long MINIMUM_TIME_TO_LIVE = 120; // Currently set to 2 minutes
	
	/**
	 * This 'maximum attempts' constant determines the number of times
	 * that a task will be attempted before it is abandoned and deleted
	 * from the queue.
	 */
	public static final int MAXIMUM_ATTEMPTS = 500;
		
	/** Determines how often the database cleaning routine should be run, in seconds. */
	private static final long TIME_BETWEEN_DATABASE_CLEANING = 3600;
		
	// Constants to identify requests from the UI
	public static final String UI_REQUEST = "uiRequest";
	public static final String UI_REQUEST_SEND_MESSAGE = "sendMessage";
	public static final String UI_REQUEST_CREATE_IDENTITY = "createIdentity";
	
	// Used when broadcasting Intents to the UI so that it can refresh the data it is displaying
	public static final String UI_NOTIFICATION = "uiNotification";
	
	// Constants that identify request for periodic background processing
	public static final String PERIODIC_BACKGROUND_PROCESSING_REQUEST = "periodicBackgroundProcessingReqest";
	public static final String BACKGROUND_PROCESSING_REQUEST = "doBackgroundProcessing";
	
	// Constants to identify data sent to this Service from the UI
	public static final String MESSAGE_ID = "messageId";
	public static final String ADDRESS_ID = "addressId";
	
	// The tasks for performing the first major function of the application: creating a new identity
	public static final String TASK_CREATE_IDENTITY = "createIdentity";
	public static final String TASK_DISSEMINATE_PUBKEY = "disseminatePubkey";
	
	// The tasks for performing the second major function of the application: sending messages
	public static final String TASK_SEND_MESSAGE = "sendMessage";
	public static final String TASK_PROCESS_OUTGOING_MESSAGE = "processOutgoingMessage";
	public static final String TASK_DISSEMINATE_MESSAGE = "disseminateMessage";
	
    private CacheWordHandler mCacheWordHandler;
			
	private static final String TAG = "BACKGROUND_SERVICE";
	
	public BackgroundService() 
	{
		super("BackgroundService");
		
		// Set up the uncaught exception handler for this thread
		Thread.setDefaultUncaughtExceptionHandler(new ExceptionHandler());
	}
	
	/**
	 * Handles requests sent to the BackgroundService via Intents
	 * 
	 * @param i - An Intent object that has been received by the 
	 * BackgroundService
	 */
	@SuppressLint("InlinedApi")
	@Override
	protected void doWakefulWork(Intent i)
	{
		try
		{
			Log.i(TAG, "BackgroundService.doWakefulWork() called");
			
			// Connect to the CacheWordService and check whether it is locked
			mCacheWordHandler = new CacheWordHandler(this);
			mCacheWordHandler.connectToService();
			SystemClock.sleep(5000); // We need to allow some extra time to connect to the CacheWordService
			if (mCacheWordHandler.isLocked())
			{
				scheduleRestart();
				closeDatabaseIfLocked();
				return;
			}
			
			// Determine whether the intent came from a request for periodic
			// background processing or from a UI request
			if (i.hasExtra(PERIODIC_BACKGROUND_PROCESSING_REQUEST))
			{
				processTasks();
			}
			
			else if (i.hasExtra(UI_REQUEST))
			{
				String uiRequest = i.getStringExtra(UI_REQUEST);
				
				TaskController taskController = new TaskController();
				
				if (uiRequest.equals(UI_REQUEST_SEND_MESSAGE))
				{
					Log.i(TAG, "Responding to UI request to run the 'send message' task");
					
					// Get the ID of the Message object from the intent
					Bundle extras = i.getExtras();
					long messageID = extras.getLong(MESSAGE_ID);
					
					// Attempt to retrieve the Message from the database. If it has been deleted by the user
					// then we should abort the sending process. 
					Message messageToSend = null;
					try
					{
						MessageProvider msgProv = MessageProvider.get(getApplicationContext());
						messageToSend = msgProv.searchForSingleRecord(messageID);
					}
					catch (RuntimeException e)
					{
						Log.i(TAG, "While running BackgroundService.onHandleIntent() and attempting to process a UI request of type\n"
								+ UI_REQUEST_SEND_MESSAGE + ", the attempt to retrieve the Message object from the database failed.\n"
								+ "The message sending process will therefore be aborted.");
						return;
					}
									
					// Create a new QueueRecord for the 'send message' task and save it to the database
					QueueRecordProcessor queueProc = new QueueRecordProcessor();
					QueueRecord queueRecord = queueProc.createAndSaveQueueRecord(TASK_SEND_MESSAGE, TimeUtils.getUnixTime(), 0, messageToSend, null, null);
					
					// Also create a new QueueRecord for re-sending this msg in the event that we do not receive an acknowledgement for it
					// before its time to live expires. If we do receive the acknowledgement before then, this QueueRecord will be deleted
					long currentTime = System.currentTimeMillis() / 1000;
					queueProc.createAndSaveQueueRecord(TASK_SEND_MESSAGE, currentTime + FIRST_ATTEMPT_TTL, 1, messageToSend, null, null);
					
					// Attempt to send the message
					taskController.sendMessage(queueRecord, messageToSend, DO_POW, FIRST_ATTEMPT_TTL, FIRST_ATTEMPT_TTL);
				}
				
				else if (uiRequest.equals(UI_REQUEST_CREATE_IDENTITY))
				{
					Log.i(TAG, "Responding to UI request to run the 'create new identity' task");
					
					// Get the ID of the Address object from the intent
					Bundle extras = i.getExtras();
					long addressId = extras.getLong(ADDRESS_ID);
					
					// Attempt to retrieve the Address from the database. If it has been deleted by the user
					// then we should abort the sending process. 
					Address address = null;
					try
					{
						AddressProvider addProv = AddressProvider.get(getApplicationContext());
						address = addProv.searchForSingleRecord(addressId);
					}
					catch (RuntimeException e)
					{
						Log.i(TAG, "While running BackgroundService.onHandleIntent() and attempting to process a UI request of type\n"
								+ UI_REQUEST_CREATE_IDENTITY + ", the attempt to retrieve the Address object from the database failed.\n"
								+ "The identity creation process will therefore be aborted.");
						return;
					}
					
					// Create a new QueueRecord for the create identity task and save it to the database
					QueueRecordProcessor queueProc = new QueueRecordProcessor();
					QueueRecord queueRecord = queueProc.createAndSaveQueueRecord(TASK_CREATE_IDENTITY, TimeUtils.getUnixTime(), 0, address, null, null);
					
					// Attempt to complete the create identity task
					taskController.createIdentity(queueRecord, DO_POW);
				}
			}
			else
			{
				Log.e(TAG, "BackgroundService.onHandleIntent() was called without a valid extra to specify what the service should do.");
			}
			
			scheduleRestart();
			closeDatabaseIfLocked();
		}
		catch (Exception e)
		{
			Log.e(TAG, "Exception occurred in BackgroundService.doWakefulWork(). The exception message was:\n"
					+ e.getMessage());
			scheduleRestart();
			closeDatabaseIfLocked();
		}
	}
	
	/**
	 * Schedules a restart of the BackgroundService
	 */
	private void scheduleRestart()
	{
		WakefulIntentService.scheduleAlarms(new AlarmScheduler(), getApplicationContext(), true);
	}
	
	/**
	 * Checks whether CacheWord is locked. If yes, this routine closes
	 * the database.
	 */
	private void closeDatabaseIfLocked()
	{
		if (mCacheWordHandler != null && mCacheWordHandler.isLocked())
		{
			DatabaseContentProvider.closeDatabase();
		}
	}
	
	/**
	 * Runs periodic background processing. <br><br>
	 * 
	 * This method will first check whether there are any QueueRecord objects saved
	 * in the database. If there are, it will attempt to complete the task recorded
	 * by each of those QueueRecords in turn. After that, it will run the 'check for
	 * messages' task. If no QueueRecords are found in the database, it will run the
	 * 'check for messages' task. 
	 */
	private void processTasks()
	{
		Log.i(TAG, "BackgroundService.processTasks() called");
		
		TaskController taskController = new TaskController();
		
		// Check the database TaskQueue table for any queued tasks
		QueueRecordProvider queueProv = QueueRecordProvider.get(getApplicationContext());
		QueueRecordProcessor queueProc = new QueueRecordProcessor();
		ArrayList<QueueRecord> queueRecords = queueProv.getAllQueueRecords();
		Log.i(TAG, "Number of QueueRecords found: " + queueRecords.size());
		
		if (queueRecords.size() > 0)
		{
			// Sort the queue records so that we will process the records with the earliest 'last attempt time' first
			Collections.sort(queueRecords);
			
			// Process each queued task in turn, removing them from the database if completed successfully
			for (QueueRecord q : queueRecords)
			{
				try
				{
					Log.i(TAG, "Found a QueueRecord with ID " + q.getId() + ", task " + q.getTask() + ", and number of attempts " + q.getAttempts());
										
					// First check how many times the task recorded by this QueueRecord has been attempted.
					// If it has been attempted a very high number of times (all without success) then we
					// will delete it.
					int attempts = q.getAttempts();
					String task = q.getTask();
					if (attempts > MAXIMUM_ATTEMPTS)
					{
						Log.d(TAG, "Deleting a QueueRecord for a task of type " + task + " because it has been attempted " + attempts + " times without success.");
						
						if (task.equals(TASK_SEND_MESSAGE))
						{
							// Update the status of the Message we were trying to send to indicate that sending has failed
							MessageProvider msgProv = MessageProvider.get(getApplicationContext());
							Message messageToSend = msgProv.searchForSingleRecord(q.getObject0Id());
							String messageStatus = App.getContext().getString(R.string.message_status_sending_failed);
							MessageStatusHandler.updateMessageStatus(messageToSend, messageStatus);
						}
						queueProc.deleteQueueRecord(q);
						continue;
					}
					
					else if (task.equals(TASK_SEND_MESSAGE))
					{
						// Attempt to retrieve the Message from the database. If it has been deleted by the user
						// then we should delete this QueueRecord and abort the sending process.
						Message messageToSend = null;
						try
						{
							MessageProvider msgProv = MessageProvider.get(getApplicationContext());
							messageToSend = msgProv.searchForSingleRecord(q.getObject0Id());
						}
						catch (RuntimeException e)
						{
							Log.i(TAG, "While running BackgroundService.processTasks() and attempting to process a task of type\n"
									+ TASK_SEND_MESSAGE + ", the attempt to retrieve the Message object from the database failed.\n"
									+ "The message sending process will therefore be aborted.");
							queueProv.deleteQueueRecord(q);
							continue;
						}
							
						// Check whether there are any existing QueueRecords which should be processed before this one.
						// If there are, this method will push the trigger time of this QueueRecord further into the future and
						// any duplicates will be deleted.
						if (checkAndAdjustQueueRecords(q))
						{
							Log.i(TAG, "Ignoring QueueRecord with ID " + q.getId() + " and task " + q.getTask() + " because there is another QueueRecord for "
									+ "the same task which should be processed first.");
							continue;
						}
						
						// Ignore any QueueRecords that have a 'trigger time' in the future
						long currentTime = System.currentTimeMillis() / 1000;
						if (q.getTriggerTime() > currentTime)
						{
							Log.i(TAG, "Ignoring a QueueRecord for a " + q.getTask() + " task because its trigger time has not been reached yet. "
									+ "Its trigger time will be reached in roughly " + TimeUtils.getTimeMessage(q.getTriggerTime() - currentTime) + ".");
							continue;
						}
						
						// Work out which TTL value we should use, then attempt to send the message
						if (q.getRecordCount() == 0) // This is the first attempt to send this message, so use the 'first attempt' TTL value
						{
							// Attempt to send the message
							taskController.sendMessage(q, messageToSend, DO_POW, FIRST_ATTEMPT_TTL, FIRST_ATTEMPT_TTL);
						}
						else // This is not the first attempt to send this message, so use the 'subsequent attempts' TTL value
						{
							// Unless we have already done so, we need to create a new QueueRecord for re-sending this msg in the event that we do not receive
							// an acknowledgement for it before its time to live expires. If we do receive the acknowledgement before then, this
							// QueueRecord will be deleted.
							if (checkForMatchingSendMsgQueueRecords(q) == false)
							{
								Log.i(TAG, "Creating a QueueRecord to re-send message with ID " + messageToSend.getId());
								currentTime = System.currentTimeMillis() / 1000;
								queueProc.createAndSaveQueueRecord(TASK_SEND_MESSAGE, currentTime + SUBSEQUENT_ATTEMPTS_TTL, q.getRecordCount() + 1, messageToSend, null, null);
							}
							
							// Attempt to send the message
							taskController.sendMessage(q, messageToSend, DO_POW, SUBSEQUENT_ATTEMPTS_TTL, SUBSEQUENT_ATTEMPTS_TTL);
						}
					}
					
					else if (task.equals(TASK_PROCESS_OUTGOING_MESSAGE))
					{
						// Attempt to retrieve the Message from the database. If it has been deleted by the user
						// then we should abort the sending process. 
						Message messageToSend = null;
						try
						{
							MessageProvider msgProv = MessageProvider.get(getApplicationContext());
							messageToSend = msgProv.searchForSingleRecord(q.getObject0Id());
						}
						catch (RuntimeException e)
						{
							Log.i(TAG, "While running BackgroundService.processTasks() and attempting to process a task of type\n"
									+ TASK_PROCESS_OUTGOING_MESSAGE + ", the attempt to retrieve the Message object from the database failed.\n"
									+ "The message sending process will therefore be aborted.");
							queueProv.deleteQueueRecord(q);
							continue;
						}
						 
						// Now retrieve the pubkey for the address we are sending the message to
						PubkeyProvider pubProv = PubkeyProvider.get(App.getContext());
						Pubkey toPubkey = pubProv.searchForSingleRecord(q.getObject1Id());
							 
						// Attempt to process and send the message
						if (q.getRecordCount() == 0)
						{
							taskController.processOutgoingMessage(q, messageToSend, toPubkey, DO_POW, FIRST_ATTEMPT_TTL);
						}
						else
						{
							taskController.processOutgoingMessage(q, messageToSend, toPubkey, DO_POW, SUBSEQUENT_ATTEMPTS_TTL);
						}
					}
					
					else if (task.equals(TASK_DISSEMINATE_MESSAGE))
					{
						// Check whether the msg payload is still valid (its time to live pay have expired)
						PayloadProvider payProv = PayloadProvider.get(getApplicationContext());
						Payload msgPayload = payProv.searchForSingleRecord(q.getObject1Id());
						boolean msgValid = new ObjectProcessor().validateObject(msgPayload.getPayload());
						if (msgValid == false)
						{
							Log.d(TAG, "Found a QueueRecord for a 'disseminate message' task with a msg payload which is due to expire soon.\n"
									+ "We will now delete this QueueRecord and msg and create a new 'process outgoing message' QueueRecord.");
							
							// Delete the msg Payload from the database
							payProv.deletePayload(msgPayload);
							
							// Delete this QueueRecord from the database
							queueProv.deleteQueueRecord(q);
							
							// Retrieve the original Message that we are sending
							MessageProvider msgProv = MessageProvider.get(getApplicationContext());
							Message messageToSend = msgProv.searchForSingleRecord(q.getObject0Id());
							
							// Retrieve the pubkey for the address we are sending the message to
							PubkeyProvider pubProv = PubkeyProvider.get(App.getContext());
							Pubkey toPubkey = pubProv.searchForSingleRecord(q.getObject2Id());
							
							// Create a new QueueRecord for the 'process outgoing message' task. This will give us a new
							// msg with an updated expiration time and proof of work
							queueProc.createAndSaveQueueRecord(BackgroundService.TASK_PROCESS_OUTGOING_MESSAGE, TimeUtils.getUnixTime(), q.getRecordCount(), messageToSend, toPubkey, null);
							
							// Move on to the next QueueRecord
							continue;
						}
						
						// Check whether an Internet connection is available. If not, move on to the next QueueRecord
						if (NetworkHelper.checkInternetAvailability() == true)
						{
							// Retrieve the pubkey for the address we are sending the message to
							PubkeyProvider pubProv = PubkeyProvider.get(App.getContext());
							Pubkey toPubkey = pubProv.searchForSingleRecord(q.getObject2Id());
								 
							// Attempt to send the msg
							taskController.disseminateMessage(q, msgPayload, toPubkey, DO_POW);
						}
						else
						{
							MessageProvider messageProv = MessageProvider.get(getApplicationContext());
							Message messageToSend = messageProv.searchForSingleRecord(q.getObject0Id());
							MessageStatusHandler.updateMessageStatus(messageToSend, getApplicationContext().getString(R.string.message_status_waiting_for_connection));
						}
					}
					
					else if (task.equals(TASK_CREATE_IDENTITY))
					{
						taskController.createIdentity(q, DO_POW);
					}
					
					else if (task.equals(TASK_DISSEMINATE_PUBKEY))
					{
						// Check whether the pubkey payload is still valid (its time to live may have expired)
						PayloadProvider payProv = PayloadProvider.get(getApplicationContext());
						Payload pubkeyPayload = payProv.searchForSingleRecord(q.getObject0Id());
						boolean pubkeyValid = new ObjectProcessor().validateObject(pubkeyPayload.getPayload());
						if (pubkeyValid)
						{
							// Check whether an Internet connection is available. If not, move on to the next QueueRecord
							if (NetworkHelper.checkInternetAvailability() == true)
							{
								// Attempt to disseminate the pubkey payload
								taskController.disseminatePubkey(q, pubkeyPayload, DO_POW);
							}
						}
						else
						{
							Log.d(TAG, "Found a QueueRecord for a 'disseminate pubkey' task with a pubkey payload which has expired or is invalid.\n"
									+ "We will now delete this QueueRecord and pubkey and create a new 'create identity' QueueRecord.");
							
							// Delete this QueueRecord from the database
							queueProv.deleteQueueRecord(q);
							
							// Retrieve the original address for which we are trying to create and disseminate a pubkey
							AddressProvider addProv = AddressProvider.get(getApplicationContext());
							Address address = addProv.searchForSingleRecord(pubkeyPayload.getRelatedAddressId());
							
							// Create a new QueueRecord for the 'create identity' task. This will give us a new
							// pubkey with an updated expiration time and proof of work
							queueProc.createAndSaveQueueRecord(TASK_CREATE_IDENTITY, TimeUtils.getUnixTime(), q.getRecordCount(), address, null, null);
						}
					}
					
					else
					{
						Log.e(TAG, "While running BackgroundService.processTasks(), a QueueRecord with an invalid task " +
								"field was found. The invalid task field was : " + task);
					}
				}
				catch (Exception e)
				{
					Log.e(TAG, "Exception occurred in BackgroundService.processTasks(). The exception message was:\n"
							+ e.getMessage());
					
					// Delete this QueueRecord from the database
					queueProv.deleteQueueRecord(q);
				}
			}
			
			runPeriodicTasks();
		}
		else // If there are no other tasks that we need to do
		{
			runPeriodicTasks();
			
			// Check whether it is time to run the 'clean database' routine. If yes then run it. 
			if (checkIfDatabaseCleaningIsRequired())
			{
				Intent intent = new Intent(getBaseContext(), DatabaseCleaningService.class);
			    intent.putExtra(DatabaseCleaningService.EXTRA_RUN_DATABASE_CLEANING_ROUTINE, true);
			    startService(intent);
			}
		}
	}
	
	/**
	 * Checks whether there is already an existing QueueRecord for sending this msg
	 * with a lower trigger time than this QueueRecord. If there is, we will push the
	 * trigger time of this QueueRecord further into the future. This is required because
	 * sometimes the task of a QueueRecord may not be completed for a long time, for
	 * example when there is no internet connection available. 
	 * 
	 * @param q - The QueueRecord to be checked
	 * 
	 * @return A boolean indicating whether a QueueRecord of greater precedence was found
	 */
	private boolean checkAndAdjustQueueRecords(QueueRecord q)
	{
		ArrayList<QueueRecord> matchingRecords = getMatchingSendMsgQueueRecords(q);
		
		// If there is more than 1 matching record, delete all but the one with the earliest trigger time. 
		// There should never be more than 2 QueueRecords for sending a given message. 
		if (matchingRecords.size() > 1)
		{
			matchingRecords = deleteDuplicateSendMsgQueueRecords(matchingRecords);
		}
		
		for (QueueRecord match : matchingRecords)
		{
			// Check whether this matching record has a trigger time earlier than the current QueueRecord
			if (match.getTriggerTime() < q.getTriggerTime())
			{
				// Push the trigger time of the current QueueRecord further into the future				
				if (match.getRecordCount() == 0) // If the QueueRecord with the earlier trigger time is the first QueueRecord for attempting to send this message
				{
					q.setTriggerTime(match.getTriggerTime() + FIRST_ATTEMPT_TTL);
				}
				else // If the QueueRecord with the earlier trigger time is not the first QueueRecord for attempting to send this message
				{
					q.setTriggerTime(match.getTriggerTime() + SUBSEQUENT_ATTEMPTS_TTL);
				}
				
				long timeTillTriggerTime =  q.getTriggerTime() - (System.currentTimeMillis() / 1000);
				Log.i(TAG, "Updating the trigger time of a QueueRecord for a " + q.getTask() + " task because there is another QueueRecord for sending the same "
						+ "message which has an earlier trigger time. The updated trigger time of this QueueRecord is " + TimeUtils.getTimeMessage(timeTillTriggerTime) + " from now.");;
				
				QueueRecordProvider.get(getApplicationContext()).updateQueueRecord(q);
				return true;
			}
		}
		return false;
	}
	
	/** 
	 * Returns a boolean indicating whether there are any other QueueRecords for
	 * sending the same message as the one referred to by the supplied QueueRecord. 
	 */
	private boolean checkForMatchingSendMsgQueueRecords (QueueRecord q)
	{		
		return getMatchingSendMsgQueueRecords(q).size() > 0;
	}
	
	/** Returns an ArrayList<QueueRecord> containing any other QueueRecords for
	 * sending the same message as the one referred to by the supplied QueueRecord. 
	 */
	private ArrayList<QueueRecord> getMatchingSendMsgQueueRecords(QueueRecord q)
	{
		// First we need to get any QueueRecords which also refer to the msg in question
		QueueRecordProvider queueProv = QueueRecordProvider.get(getApplicationContext());
		ArrayList<QueueRecord> matchingRecords = queueProv.searchQueueRecords(QueueRecordsTable.COLUMN_OBJECT_0_ID, String.valueOf(q.getObject0Id()));
				
		// Now iterate through the list and remove any QueueRecords which are either
		// i)  The same as the current record
		// ii) Not QueueRecord for one of the three 'send message' tasks
		Iterator<QueueRecord> iterator = matchingRecords.iterator();
		while(iterator.hasNext())
		{
			QueueRecord match = iterator.next();
			
			// Remove the current QueueRecord from the list of 'matching' QueueRecods
		    if(match.getId() == (q.getId()))
		    {
		    	iterator.remove();
		    }
			
			// Filter the list of QueueRecords so that we only consider two QueueRecords to match if they refer to the same task
		    else if (match.getTask().equals(q.getTask()) == false)
			{
		    	iterator.remove();
			}
		}
				
		return matchingRecords;
	}
	
	/**
	 * Takes an ArrayList<QueueRecord> of duplicate QueueRecords for sending a single message
	 * and returns only the one with the earliest trigger time
	 */
	private ArrayList<QueueRecord> deleteDuplicateSendMsgQueueRecords (ArrayList<QueueRecord> matchingRecords)
	{
		// Find the QueueRecord with the earliest trigger time
		long earliestTriggerTime = 0;
		long earliestRecordId = 0;
		for (QueueRecord q : matchingRecords)
		{
			if (earliestTriggerTime == 0)
			{
				earliestTriggerTime = q.getTriggerTime();
				earliestRecordId = q.getId();
			}
			
			else if (q.getTriggerTime() < earliestTriggerTime)
			{
				earliestTriggerTime = q.getTriggerTime();
				earliestRecordId = q.getId();
			}
		}
		
		// Return only the QueueRecord with the earliest trigger time
		ArrayList<QueueRecord> recordToReturn = new ArrayList<QueueRecord>();
		for (QueueRecord q : matchingRecords)
		{
			if (q.getId() == earliestRecordId)
			{
				recordToReturn.add(q);
			}
			else
			{
				new QueueRecordProcessor().deleteQueueRecord(q);
			}
		}
		
		return recordToReturn;
	}
	
	/**
	 * Runs the tasks that must be done periodically, e.g. checking for new msgs. 
	 */
	private void runPeriodicTasks()
	{
		try
		{
			Log.i(TAG, "BackgroundService.runPeriodicTasks() called");
			
			runCheckForMessagesTask();
			runCheckIfPubkeyReDisseminationIsDueTask();
		}
		catch (Exception e)
		{
			Log.e(TAG, "Exception occurred in BackgroundService.runPeriodicTasks(). The exception message was:\n"
					+ e.getMessage());
		}
	}
	
	/**
	 * This method runs the 'check for messages and send acks' task, via
	 * the TaskController. <br><br>
	 * 
	 * Note that we do NOT create QueueRecords for this task, because it
	 * is a default action that will be carried out regularly anyway.
	 */
	private void runCheckForMessagesTask()
	{
		Log.i(TAG, "BackgroundService.runCheckForMessagesTask() called");
		
		// First check whether an Internet connection is available. If not, we cannot proceed. 
		if (NetworkHelper.checkInternetAvailability() == true)
		{
			// Only run this task if we have at least one Address!
			AddressProvider addProv = AddressProvider.get(getApplicationContext());
			ArrayList<Address> myAddresses = addProv.getAllAddresses();
			if (myAddresses.size() > 0)
			{
				// Attempt to complete the task
				TaskController taskController = new TaskController();
				taskController.checkForMessagesAndSendAcks();
			}
			else
			{
				Log.i(TAG, "No Addresses were found in the application database, so we will not run the 'Check for messages' task");
			}
		}
	}
	
	/**
	 * This method runs the 'check if pubkey re-dissemination is due' task, via
	 * the TaskController. <br><br>
	 * 
	 * Note that we do NOT create QueueRecords for this task, because it is a
	 * default action that will be carried out regularly anyway. 
	 */
	private void runCheckIfPubkeyReDisseminationIsDueTask()
	{
		Log.i(TAG, "BackgroundService.runCheckIfPubkeyReDisseminationIsDueTask() called");
		
		// Only run this task if we have at least one Address!
		AddressProvider addProv = AddressProvider.get(getApplicationContext());
		ArrayList<Address> myAddresses = addProv.getAllAddresses();
		if (myAddresses.size() > 0)
		{
			// First delete any duplicate pubkeys and corresponding objects
			deleteDuplicatePubkeys(myAddresses);
			
			// Attempt to complete the task
			TaskController taskController = new TaskController();
			taskController.checkIfPubkeyDisseminationIsDue(DO_POW);
		}
		else
		{
			Log.i(TAG, "No Addresses were found in the application database, so we will not run the 'Check if pubkey re-dissemination is due' task");
		}
	}
	
	/**
	 * Deletes any duplicate pubkeys and any Payloads or QueueRecords that
	 * correspond to them. 
	 */
	private void deleteDuplicatePubkeys(ArrayList<Address> myAddresses)
	{
		try
		{
			// First find any duplicate Pubkeys
			PubkeyProvider pubProv = PubkeyProvider.get(getApplicationContext());
			ArrayList<Pubkey> duplicatePubkeys = new ArrayList<Pubkey>();
			for (Address a : myAddresses)
			{
				// Find any duplicate pubkeys
				ArrayList<Pubkey> correspondingPubkeys = pubProv.searchPubkeys(PubkeysTable.COLUMN_RIPE_HASH, Base64.encodeToString(ByteUtils.stripLeadingZeros(a.getRipeHash()), Base64.DEFAULT));
				
				if (correspondingPubkeys.size() > 1)
				{
					// Mark all the pubkeys as potential duplicates 
					for (Pubkey p : correspondingPubkeys)
					{
						duplicatePubkeys.add(p);
					}
					
					// Remove the pubkey with the latest expiration time - this is the one to keep
					long latestTimeValue = 0;
					for (Pubkey p : correspondingPubkeys)
					{
						if (latestTimeValue == 0)
						{
							latestTimeValue = p.getExpirationTime();
						}
						else if (p.getExpirationTime() > latestTimeValue)
						{
							latestTimeValue = p.getExpirationTime();
						}
					}
					for (Pubkey p : correspondingPubkeys)
					{
						if (p.getExpirationTime() == latestTimeValue)
						{
							duplicatePubkeys.remove(p);
						}
					}					
				}
			}
			
			Log.i(TAG, "Found " + duplicatePubkeys.size() + " duplicate Pubkey(s)");
			
			for (Pubkey p : duplicatePubkeys)
			{
				try
				{
					// Get the corresponding address
					AddressProvider addProv = AddressProvider.get(getApplicationContext());
					Address correspondingAddress = addProv.searchForSingleRecord(p.getCorrespondingAddressId());
					
					// Delete their corresponding pubkey Payloads and any QueueRecords for disseminating those payloads
					PayloadProvider payProv = PayloadProvider.get(getApplicationContext());
					ArrayList<Payload> correspondingPayloads = payProv.searchPayloads(PayloadsTable.COLUMN_RELATED_ADDRESS_ID, String.valueOf(correspondingAddress.getId()));
					for (Payload payload : correspondingPayloads)
					{
						payProv.deletePayload(payload);
						
						// Delete any QueueRecords for disseminating this Payload
						QueueRecordProvider queueProv = QueueRecordProvider.get(getApplicationContext());
						ArrayList<QueueRecord> allQueueRecords = queueProv.getAllQueueRecords();
						for (QueueRecord q : allQueueRecords)
						{
							if (q.getTask().equals(QueueRecordProcessor.TASK_DISSEMINATE_PUBKEY) && q.getObject0Id() == payload.getId())
							{
								queueProv.deleteQueueRecord(q);
							}
						}
					}
					
					// Delete the duplicate Pubkey
					pubProv.deletePubkey(p);
				}
				catch (Exception e)
				{
					Log.e(TAG, "Exception occurred while processing duplicate pubkeys in BackgroundService.deleteDuplicatePubkeys(). The exception message was:\n"
							+ e.getMessage() + "\n Moving on to the next duplicate pubkey.");
					continue;
				}
			}
		}
		catch (Exception e)
		{
			Log.e(TAG, "Exception occurred in BackgroundService.deleteDuplicatePubkeys(). The exception message was:\n"
					+ e.getMessage());
		}
	}
	
	/**
	 * Determines whether it is time to run the 'clean database' routine,
	 * which deletes defunct data. This is based on the period of time since
	 * this routine was last run. 
	 * 
	 * @return A boolean indicating whether or not the 'clean database' routine
	 * should be run.
	 */
	private boolean checkIfDatabaseCleaningIsRequired()
	{
		Log.i(TAG, "BackgroundService.checkIfDatabaseCleaningIsRequired() called");
		
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		long currentTime = System.currentTimeMillis() / 1000;
		long lastDataCleanTime = prefs.getLong(DatabaseCleaningService.LAST_DATABASE_CLEAN_TIME, 0);
		
		if (lastDataCleanTime == 0)
		{
			return true;
		}
		else
		{
			long timeSinceLastDataClean = currentTime - lastDataCleanTime;
			if (timeSinceLastDataClean > TIME_BETWEEN_DATABASE_CLEANING)
			{
				return true;
			}
			else
			{
				long timeTillNextDatabaseClean = TIME_BETWEEN_DATABASE_CLEANING - timeSinceLastDataClean;
				Log.i(TAG, "The database cleaning service was last run " + TimeUtils.getTimeMessage(timeSinceLastDataClean) + " ago\n" + 
						   "The database cleaning service will be run again in " + TimeUtils.getTimeMessage(timeTillNextDatabaseClean));
				return false;
			}
		}
	}
	
	@Override
	public void onDestroy()
	{
    	super.onDestroy();
    	if (mCacheWordHandler != null)
    	{
    		mCacheWordHandler.disconnectFromService();
    	}
	}
	
	@SuppressLint("InlinedApi")
	@Override
	public void onCacheWordLocked()
	{
		Log.i(TAG, "BackgroundService.onCacheWordLocked() called.");
		// Nothing to do here currently
	}

	@Override
	public void onCacheWordOpened()
	{
		Log.i(TAG, "BackgroundService.onCacheWordOpened() called.");
		// Nothing to do here currently
	}
	
	@Override
	public void onCacheWordUninitialized()
	{
		Log.i(TAG, "BackgroundService.onCacheWordUninitialized() called.");
		// Nothing to do here currently
	}
}