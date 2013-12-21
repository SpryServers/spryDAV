/*******************************************************************************
 * Copyright (c) 2013 Richard Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 ******************************************************************************/
package at.bitfire.davdroid.resource;

import java.util.ArrayList;
import java.util.LinkedList;

import lombok.Cleanup;
import android.accounts.Account;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderOperation.Builder;
import android.content.ContentUris;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.CalendarContract;
import android.util.Log;

public abstract class LocalCollection<T extends Resource> {
	private static final String TAG = "davdroid.LocalCollection";
	
	protected Account account;
	protected ContentProviderClient providerClient;
	protected ArrayList<ContentProviderOperation> pendingOperations = new ArrayList<ContentProviderOperation>();

	
	// database fields
	
	abstract protected Uri entriesURI();

	abstract protected String entryColumnAccountType();
	abstract protected String entryColumnAccountName();

	abstract protected String entryColumnParentID();
	abstract protected String entryColumnID();
	abstract protected String entryColumnRemoteName();
	abstract protected String entryColumnETag();
	
	abstract protected String entryColumnDirty();
	abstract protected String entryColumnDeleted();
	
	abstract protected String entryColumnUID();
	

	LocalCollection(Account account, ContentProviderClient providerClient) {
		this.account = account;
		this.providerClient = providerClient;
	}
	

	// collection operations
	
	abstract public long getId();
	abstract public String getCTag();
	abstract public void setCTag(String cTag);

	
	// content provider (= database) querying
	
	public Resource[] findDirty() throws LocalStorageException {
		String where = entryColumnDirty() + "=1";
		if (entryColumnParentID() != null)
			where += " AND " + entryColumnParentID() + "=" + String.valueOf(getId());
		try {
			@Cleanup Cursor cursor = providerClient.query(entriesURI(),
					new String[] { entryColumnID(), entryColumnRemoteName(), entryColumnETag() },
					where, null, null);
			LinkedList<T> dirty = new LinkedList<T>();
			while (cursor != null && cursor.moveToNext()) {
				long id = cursor.getLong(0);
				try {
					T resource = findById(id, true); 
					dirty.add(resource);
				} catch (RecordNotFoundException e) {
					Log.w(TAG, "Couldn't load dirty resource: " + ContentUris.appendId(entriesURI().buildUpon(), id), e);
				}
			}
			return dirty.toArray(new Resource[0]);
		} catch(RemoteException ex) {
			throw new LocalStorageException(ex);
		}
	}

	public Resource[] findDeleted() throws LocalStorageException {
		String where = entryColumnDeleted() + "=1";
		if (entryColumnParentID() != null)
			where += " AND " + entryColumnParentID() + "=" + String.valueOf(getId());
		try {
			@Cleanup Cursor cursor = providerClient.query(entriesURI(),
					new String[] { entryColumnID(), entryColumnRemoteName(), entryColumnETag() },
					where, null, null);
			LinkedList<T> deleted = new LinkedList<T>();
			while (cursor != null && cursor.moveToNext()) {
				long id = cursor.getLong(0);
				try {
					T resource = findById(id, false);
					deleted.add(resource);
				} catch (RecordNotFoundException e) {
					Log.w(TAG, "Couldn't load resource marked for deletion: " + ContentUris.appendId(entriesURI().buildUpon(), id), e);
				}
			}
			return deleted.toArray(new Resource[0]);
		} catch(RemoteException ex) {
			throw new LocalStorageException(ex);
		}
	}

	public Resource[] findNew() throws LocalStorageException {
		String where = entryColumnDirty() + "=1 AND " + entryColumnRemoteName() + " IS NULL";
		if (entryColumnParentID() != null)
			where += " AND " + entryColumnParentID() + "=" + String.valueOf(getId());
		try {
			@Cleanup Cursor cursor = providerClient.query(entriesURI(),
					new String[] { entryColumnID() },
					where, null, null);
			LinkedList<T> fresh = new LinkedList<T>();
			while (cursor != null && cursor.moveToNext()) {
				long id = cursor.getLong(0);
				try {
					T resource = findById(id, true);
					resource.initialize();
		
					// new record: set generated UID + remote file name in database
					pendingOperations.add(ContentProviderOperation
							.newUpdate(ContentUris.withAppendedId(entriesURI(), resource.getLocalID()))
							.withValue(entryColumnUID(), resource.getUid())
							.withValue(entryColumnRemoteName(), resource.getName())
							.build());
					
					fresh.add(resource);
				} catch (RecordNotFoundException e) {
					Log.w(TAG, "Couldn't load fresh resource: " + ContentUris.appendId(entriesURI().buildUpon(), id), e);
				}
			}
			return fresh.toArray(new Resource[0]);
		} catch(RemoteException ex) {
			throw new LocalStorageException(ex);
		}
	}
	
	public T findById(long localID, boolean populate) throws LocalStorageException {
		try {
			@Cleanup Cursor cursor = providerClient.query(ContentUris.withAppendedId(entriesURI(), localID),
					new String[] { entryColumnRemoteName(), entryColumnETag() }, null, null, null);
			if (cursor != null && cursor.moveToNext()) {
				T resource = newResource(localID, cursor.getString(0), cursor.getString(1));
				if (populate)
					populate(resource);
				return resource;
			} else
				throw new RecordNotFoundException();
		} catch(RemoteException ex) {
			throw new LocalStorageException(ex);
		}
	}
	
	public T findByRemoteName(String remoteName, boolean populate) throws LocalStorageException {
		try {
			@Cleanup Cursor cursor = providerClient.query(entriesURI(),
					new String[] { entryColumnID(), entryColumnRemoteName(), entryColumnETag() },
					entryColumnRemoteName() + "=?", new String[] { remoteName }, null);
			if (cursor != null && cursor.moveToNext()) {
				T resource = newResource(cursor.getLong(0), cursor.getString(1), cursor.getString(2));
				if (populate)
					populate(resource);
				return resource;
			} else
				throw new RecordNotFoundException();
		} catch(RemoteException ex) {
			throw new LocalStorageException(ex);
		}
	}


	public abstract void populate(Resource record) throws LocalStorageException;
	
	protected void queueOperation(Builder builder) {
		if (builder != null)
			pendingOperations.add(builder.build());
	}

	
	// create/update/delete
	
	abstract public T newResource(long localID, String resourceName, String eTag);
	
	public void add(Resource resource) {
		int idx = pendingOperations.size();
		pendingOperations.add(
				buildEntry(ContentProviderOperation.newInsert(entriesURI()), resource)
				.withYieldAllowed(true)
				.build());
		
		addDataRows(resource, -1, idx);
	}
	
	public void updateByRemoteName(Resource remoteResource) throws LocalStorageException {
		T localResource = findByRemoteName(remoteResource.getName(), false);
		pendingOperations.add(
				buildEntry(ContentProviderOperation.newUpdate(ContentUris.withAppendedId(entriesURI(), localResource.getLocalID())), remoteResource)
				.withValue(entryColumnETag(), remoteResource.getETag())
				.withYieldAllowed(true)
				.build());
		
		removeDataRows(localResource);
		addDataRows(remoteResource, localResource.getLocalID(), -1);
	}

	public void delete(Resource resource) {
		pendingOperations.add(ContentProviderOperation
				.newDelete(ContentUris.withAppendedId(entriesURI(), resource.getLocalID()))
				.withYieldAllowed(true)
				.build());
	}

	public abstract void deleteAllExceptRemoteNames(Resource[] remoteResources);
	
	public void clearDirty(Resource resource) {
		pendingOperations.add(ContentProviderOperation
				.newUpdate(ContentUris.withAppendedId(entriesURI(), resource.getLocalID()))
				.withValue(entryColumnDirty(), 0).build());
	}

	public void commit() throws LocalStorageException {
		if (!pendingOperations.isEmpty())
			try {
				Log.i(TAG, "Committing " + pendingOperations.size() + " operations");
				providerClient.applyBatch(pendingOperations);
				pendingOperations.clear();
			} catch (RemoteException ex) {
				throw new LocalStorageException(ex);
			} catch(OperationApplicationException ex) {
				throw new LocalStorageException(ex);
			}
	}

	
	// helpers
	
	protected Uri syncAdapterURI(Uri baseURI) {
		return baseURI.buildUpon()
				.appendQueryParameter(entryColumnAccountType(), account.type)
				.appendQueryParameter(entryColumnAccountName(), account.name)
				.appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
				.build();
	}
	
	protected Builder newDataInsertBuilder(Uri dataUri, String refFieldName, long raw_ref_id, Integer backrefIdx) {
		Builder builder = ContentProviderOperation.newInsert(syncAdapterURI(dataUri));
		if (backrefIdx != -1)
			return builder.withValueBackReference(refFieldName, backrefIdx);
		else
			return builder.withValue(refFieldName, raw_ref_id);
	}
	
	
	// content builders

	protected abstract Builder buildEntry(Builder builder, Resource resource);
	
	protected abstract void addDataRows(Resource resource, long localID, int backrefIdx);
	protected abstract void removeDataRows(Resource resource);
}
