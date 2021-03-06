package com.todoroo.astrid.gtasks;

import java.io.IOException;
import java.util.Date;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.content.Intent;
import android.os.Bundle;

import com.google.api.client.googleapis.extensions.android2.auth.GoogleAccountManager;
import com.google.api.services.tasks.v1.model.Tasks;
import com.timsu.astrid.R;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.ContextManager;
import com.todoroo.andlib.utility.AndroidUtilities;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.data.Metadata;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.gtasks.api.GtasksApiUtilities;
import com.todoroo.astrid.gtasks.api.GtasksService;
import com.todoroo.astrid.gtasks.auth.GtasksTokenValidator;
import com.todoroo.astrid.gtasks.sync.GtasksSyncOnSaveService;
import com.todoroo.astrid.service.TaskService;
import com.todoroo.astrid.test.DatabaseTestCase;

public class GtasksSyncOnSaveTest extends DatabaseTestCase {

    @Autowired TaskService taskService;
    @Autowired GtasksSyncOnSaveService gtasksSyncOnSaveService;
    @Autowired GtasksMetadataService gtasksMetadataService;
    @Autowired GtasksPreferenceService gtasksPreferenceService;

    private GtasksService gtasksService;
    private boolean initialized = false;
    private boolean bypassTests = false;
    private static final String TEST_ACCOUNT = "sync_tester2@astrid.com";
    private static String DEFAULT_LIST = "@default";

    //Have to wait a long time because sync on save happens in another thread--currently no way to know when finished
    private static final long TIME_TO_WAIT = 8000L;


    public void testSyncOnCreate() throws IOException {
        if(bypassTests) return;
        performBasicCreation("");
    }

    private Task performBasicCreation(String appendToTitle) throws IOException {
        String title = "Created task" + appendToTitle;
        Task localTask = setupLocalTaskModel(title);
        taskService.save(localTask);

        AndroidUtilities.sleepDeep(TIME_TO_WAIT);

        com.google.api.services.tasks.v1.model.Task remoteTask = getRemoteTaskForLocalId(localTask.getId());
        assertEquals(title, remoteTask.title);
        return localTask;
    }

    private Task setupLocalTaskModel(String title) {
        Task localTask = new Task();
        localTask.setValue(Task.TITLE, title);
        return localTask;
    }

    private com.google.api.services.tasks.v1.model.Task getRemoteTaskForLocalId(long localId) throws IOException {
        Metadata gtasksMetadata = gtasksMetadataService.getTaskMetadata(localId);
        assertNotNull(gtasksMetadata);
        com.google.api.services.tasks.v1.model.Task remoteTask = gtasksService.getGtask(DEFAULT_LIST, gtasksMetadata.getValue(GtasksMetadata.ID));
        assertNotNull(remoteTask);
        return remoteTask;
    }

    public void testSyncOnTitleUpdate() throws IOException {
        if(bypassTests) return;
        Task localTask = performBasicCreation("-title will change");

        String newTitle = "Title has changed!";
        localTask.setValue(Task.TITLE, newTitle);
        taskService.save(localTask);

        AndroidUtilities.sleepDeep(TIME_TO_WAIT);

        com.google.api.services.tasks.v1.model.Task remoteTask = getRemoteTaskForLocalId(localTask.getId());
        assertEquals(newTitle, remoteTask.title);
    }

    public void testSyncOnDueDateUpdate() throws IOException {
        if(bypassTests) return;
        Task localTask = performBasicCreation("-due date will change");

        long dueDate = new Date(115, 2, 14).getTime();
        localTask.setValue(Task.DUE_DATE, dueDate);
        taskService.save(localTask);

        AndroidUtilities.sleepDeep(TIME_TO_WAIT);

        com.google.api.services.tasks.v1.model.Task remoteTask = getRemoteTaskForLocalId(localTask.getId());
        assertEquals(dueDate, GtasksApiUtilities.gtasksDueTimeToUnixTime(remoteTask.due, 0));
    }

    public void testSyncOnNotesUpdate() throws IOException {
        if(bypassTests) return;
        Task localTask = performBasicCreation("-notes will change");

        String notes = "Noted!";
        localTask.setValue(Task.NOTES, notes);
        taskService.save(localTask);

        AndroidUtilities.sleepDeep(TIME_TO_WAIT);

        com.google.api.services.tasks.v1.model.Task remoteTask = getRemoteTaskForLocalId(localTask.getId());
        assertEquals(notes, remoteTask.notes);
    }

    public void testSyncOnCompleted() throws IOException {
        if(bypassTests) return;
        Task localTask = performBasicCreation("-will be completed");

        long completionDate = DateUtilities.now();
        localTask.setValue(Task.COMPLETION_DATE, completionDate);
        taskService.save(localTask);

        AndroidUtilities.sleepDeep(TIME_TO_WAIT);

        com.google.api.services.tasks.v1.model.Task remoteTask = getRemoteTaskForLocalId(localTask.getId());
        assertEquals("completed", remoteTask.status);
        assertEquals(completionDate, GtasksApiUtilities.gtasksCompletedTimeToUnixTime(remoteTask.completed, 0));
    }

    public void testSyncOnDeleted() throws IOException {
        if(bypassTests) return;
        Task localTask = performBasicCreation("-will be deleted");

        long deletionDate = DateUtilities.now();
        localTask.setValue(Task.DELETION_DATE, deletionDate);
        taskService.save(localTask);

        AndroidUtilities.sleepDeep(TIME_TO_WAIT);

        com.google.api.services.tasks.v1.model.Task remoteTask = getRemoteTaskForLocalId(localTask.getId());
        assertTrue(remoteTask.deleted);
        assertFalse(taskWithTitleExists(localTask.getValue(Task.TITLE)));
    }

    private boolean taskWithTitleExists(String title) throws IOException {
        Tasks allTasks = gtasksService.getAllGtasksFromListId(DEFAULT_LIST, false, false);
        if (allTasks.items != null) {
            for (com.google.api.services.tasks.v1.model.Task t : allTasks.items) {
                if (t.title.equals(title))
                    return true;
            }
        }
        return false;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        if (!initialized) {
            initializeTestService();
            gtasksSyncOnSaveService.initialize();
            initialized = true;
            Preferences.setBoolean(R.string.gtasks_GPr_sync_on_save_key, true);
        }

        setupTestList();
    }

    private void initializeTestService() throws Exception {
        GoogleAccountManager manager = new GoogleAccountManager(ContextManager.getContext());
        Account[] accounts = manager.getAccounts();

        Account toUse = null;
        for (Account a : accounts) {
            if (a.name.equals(TEST_ACCOUNT)) {
                toUse = a;
                break;
            }
        }
        if (toUse == null) {
            if(accounts.length == 0) {
                bypassTests = true;
                return;
            }
            toUse = accounts[0];
        }

        Preferences.setString(GtasksPreferenceService.PREF_USER_NAME, toUse.name);
        AccountManagerFuture<Bundle> accountManagerFuture = manager.manager.getAuthToken(toUse, "oauth2:https://www.googleapis.com/auth/tasks", true, null, null);

        Bundle authTokenBundle = accountManagerFuture.getResult();
        if (authTokenBundle.containsKey(AccountManager.KEY_INTENT)) {
            Intent i = (Intent) authTokenBundle.get(AccountManager.KEY_INTENT);
            ContextManager.getContext().startActivity(i);
            return;
        }
        String authToken = authTokenBundle.getString(AccountManager.KEY_AUTHTOKEN);
        authToken = GtasksTokenValidator.validateAuthToken(authToken);
        gtasksPreferenceService.setToken(authToken);

        gtasksService = new GtasksService(authToken);

        initialized = true;
    }

    private void setupTestList() throws Exception {
        Tasks defaultListTasks = gtasksService.getAllGtasksFromListId(DEFAULT_LIST, false, false);
        if (defaultListTasks.items != null) {
            for (com.google.api.services.tasks.v1.model.Task t : defaultListTasks.items) {
                gtasksService.deleteGtask(DEFAULT_LIST, t.id);
            }
        }
    }
}
