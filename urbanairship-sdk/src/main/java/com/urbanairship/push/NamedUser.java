/* Copyright 2016 Urban Airship and Contributors */

package com.urbanairship.push;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import com.urbanairship.AirshipComponent;
import com.urbanairship.Logger;
import com.urbanairship.PreferenceDataStore;
import com.urbanairship.UAirship;
import com.urbanairship.job.Job;
import com.urbanairship.job.JobDispatcher;
import com.urbanairship.util.UAStringUtil;

import java.util.UUID;

/**
 * The named user is an alternate method of identifying the device. Once a named
 * user is associated to the device, it can be used to send push notifications
 * to the device.
 */
public class NamedUser extends AirshipComponent {

    /**
     * The change token tracks the start of setting the named user ID.
     */
    private static final String CHANGE_TOKEN_KEY = "com.urbanairship.nameduser.CHANGE_TOKEN_KEY";

    /**
     * The named user ID.
     */
    private static final String NAMED_USER_ID_KEY = "com.urbanairship.nameduser.NAMED_USER_ID_KEY";

    /**
     * The maximum length of the named user ID string.
     */
    private static final int MAX_NAMED_USER_ID_LENGTH = 128;

    private final PreferenceDataStore preferenceDataStore;
    private final Context context;
    private final Object lock = new Object();
    private final JobDispatcher jobDispatcher;
    private NamedUserJobHandler namedUserJobHandler;

    /**
     * Creates a NamedUser.
     *
     * @param context The application context.
     * @param preferenceDataStore The preferences data store.
     */
    public NamedUser(@NonNull Context context, @NonNull PreferenceDataStore preferenceDataStore) {
        this(context, preferenceDataStore, JobDispatcher.shared(context));
    }

    @VisibleForTesting
    NamedUser(@NonNull Context context, @NonNull PreferenceDataStore preferenceDataStore, JobDispatcher dispatcher) {
        this.context = context.getApplicationContext();
        this.preferenceDataStore = preferenceDataStore;
        this.jobDispatcher = dispatcher;
    }

    @Override
    protected void init() {
        // Start named user update
        dispatchNamedUserUpdateJob();

        // Update named user tags if we have a named user
        if (getId() != null) {
            dispatchUpdateTagGroupsJob();
        }
    }

    @Override
    protected int onPerformJob(@NonNull UAirship airship, Job job) {
        if (namedUserJobHandler == null) {
            namedUserJobHandler = new NamedUserJobHandler(context, airship, preferenceDataStore);
        }

        return namedUserJobHandler.performJob(job);
    }

    /**
     * Returns the named user ID.
     *
     * @return The named user ID as a string or null if it does not exist.
     */
    public String getId() {
        return preferenceDataStore.getString(NAMED_USER_ID_KEY, null);
    }

    /**
     * Forces a named user update.
     */
    public void forceUpdate() {
        Logger.debug("NamedUser - force named user update.");
        updateChangeToken();
        dispatchNamedUserUpdateJob();
    }

    /**
     * Sets the named user ID.
     * </p>
     * To associate the named user ID, its length must be greater than 0 and less than 129 characters.
     * To disassociate the named user ID, its value must be null.
     *
     * @param namedUserId The named user ID string.
     */
    public void setId(@Nullable String namedUserId) {
        String id = null;
        if (namedUserId != null) {
            id = namedUserId.trim();
            if (UAStringUtil.isEmpty(id) || id.length() > MAX_NAMED_USER_ID_LENGTH) {
                Logger.error("Failed to set named user ID. " +
                        "The named user ID must be greater than 0 and less than 129 characters.");
                return;
            }
        }

        synchronized (lock) {
            // check if the newly trimmed ID matches with currently stored ID
            boolean isEqual = getId() == null ? id == null : getId().equals(id);

            // if the IDs don't match or ID is set to null and current token is null (re-install case), then update.
            if (!isEqual || (getId() == null && getChangeToken() == null)) {
                preferenceDataStore.put(NAMED_USER_ID_KEY, id);

                // Update the change token.
                updateChangeToken();

                // When named user ID change, clear pending named user tags.
                dispatchClearTagsJob();

                dispatchNamedUserUpdateJob();
            } else {
                Logger.debug("NamedUser - Skipping update. Named user ID trimmed already matches existing named user: " + getId());
            }
        }
    }

    /**
     * Edit the named user tags.
     *
     * @return The TagGroupsEditor.
     */
    public TagGroupsEditor editTagGroups() {
        return new TagGroupsEditor(NamedUserJobHandler.ACTION_APPLY_TAG_GROUP_CHANGES, NamedUser.class, jobDispatcher);
    }

    /**
     * Gets the named user ID change token.
     *
     * @return The named user ID change token.
     */
    @Nullable
    String getChangeToken() {
        return preferenceDataStore.getString(CHANGE_TOKEN_KEY, null);
    }

    /**
     * Modify the change token to force an update.
     */
    private void updateChangeToken() {
        preferenceDataStore.put(CHANGE_TOKEN_KEY, UUID.randomUUID().toString());
    }

    /**
     * Disassociate the named user only if the named user ID is really null.
     */
    synchronized void disassociateNamedUserIfNull() {
        if (UAStringUtil.equals(getId(), null)) {
            setId(null);
        }
    }

    /**
     * Dispatches a job to update the named user.
     */
    void dispatchNamedUserUpdateJob() {
        Job job = Job.newBuilder(NamedUserJobHandler.ACTION_UPDATE_NAMED_USER)
                .setAirshipComponent(NamedUser.class)
                .build();

        jobDispatcher.dispatch(job);
    }

    /**
     * Dispatches a job to clear pending named user tags.
     */
    void dispatchClearTagsJob() {
        Job job = Job.newBuilder(NamedUserJobHandler.ACTION_CLEAR_PENDING_NAMED_USER_TAGS)
                     .setAirshipComponent(NamedUser.class)
                     .build();

        jobDispatcher.dispatch(job);
    }

    /**
     * Dispatches a job to update the named user tag groups.
     */
    void dispatchUpdateTagGroupsJob() {
        Job job = Job.newBuilder(NamedUserJobHandler.ACTION_UPDATE_TAG_GROUPS)
                     .setAirshipComponent(NamedUser.class)
                     .build();

        jobDispatcher.dispatch(job);
    }
}
