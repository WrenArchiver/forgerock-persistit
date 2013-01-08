/**
 * Copyright © 2012 Akiban Technologies, Inc.  All rights reserved.
 * 
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Public License v1.0 which
 * accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * This program may also be available under different license terms.
 * For more information, see www.akiban.com or contact licensing@akiban.com.
 * 
 * Contributors:
 * Akiban Technologies, Inc.
 */

package com.persistit;

import static com.persistit.TransactionIndex.tss2vh;
import static com.persistit.TransactionIndex.vh2ts;
import static com.persistit.TransactionStatus.ABORTED;
import static com.persistit.TransactionStatus.PRIMORDIAL;
import static com.persistit.TransactionStatus.TIMED_OUT;
import static com.persistit.TransactionStatus.UNCOMMITTED;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import com.persistit.Version.PrunableVersion;
import com.persistit.Version.VersionCreator;
import com.persistit.exception.PersistitException;
import com.persistit.exception.PersistitInterruptedException;
import com.persistit.exception.RollbackException;
import com.persistit.exception.TimeoutException;
import com.persistit.exception.WWRetryException;

/**
 * <p>
 * Transactionally manage multiple versions of objects. For example, an object
 * which caches state created and either committed or rolled back by a
 * transaction may be use a TimelyResource to hold its versions. Each version
 * must implement {@link Version}. The {@link Version#prune()} method is called
 * when <code>TimelyResource</code> detects that the version is obsolete.
 * </p>
 * <p>
 * <code>TimelyResource</code> is parameterized with two types T and V. T is the
 * type of a "container" class that utilizes a <code>TimelyResource</code> and V
 * is the concrete type of a {@link Version} which holds information about one
 * version. For example, a
 * <code>TimelyResource&lt;Tree, Tree.TreeVersion&gt;</code> is used to hold
 * version information for a {@link Tree}.
 * </p>
 * <p>
 * The method {@link #addVersion(Version, Transaction)} attempts to add a new
 * version on behalf of the supplied transaction.
 * </p>
 * <p>
 * The method {@link #getVersion(Transaction)} returns the snapshot version
 * associated with the transaction, in other words, an instance of a P which was
 * either committed last before this transaction started, or which was created
 * by this transaction. If there is no such version the method returns
 * <code>null</code>.
 * </p>
 * <p>
 * The method {@link #getVersion(Transaction, VersionCreator)} does the same
 * thing, except that if there is no application version the
 * {@link VersionCreator#createVersion(TimelyResource)} method s called to
 * construct a new version.
 * </p>
 * 
 * @author peter
 * 
 * @param <T>
 * @param <V>
 */
public class TimelyResource<T extends Object, V extends Version> {

    private final Persistit _persistit;
    private final T _container;
    private volatile Entry _first;

    public TimelyResource(final Persistit persistit, final T container) {
        _persistit = persistit;
        _container = container;
        _persistit.addTimelyResource(this);
    }

    public T getContainer() {
        return _container;
    }

    private long tss2v(final Transaction txn) {
        if (txn == null || !txn.isActive()) {
            return tss2vh(_persistit.getTimestampAllocator().updateTimestamp(), 0);
        } else {
            return tss2vh(txn.getStartTimestamp(), txn.getStep());
        }
    }

    public synchronized void delete(final Transaction txn) throws RollbackException, PersistitException {
        if (_first != null) {
            final V resource = _first.getResource();
            final Entry entry = new Entry(tss2v(txn), resource);
            entry.setDeleted();
            addVersion(entry, txn);
        }
    }

    public void addVersion(final V resource, final Transaction txn) throws PersistitException, RollbackException {
        if (resource == null) {
            throw new NullPointerException("Null resource");
        }
        addVersion(new Entry(tss2v(txn), resource), txn);
    }

    public V getVersion(final Transaction txn) throws TimeoutException, PersistitInterruptedException {
        if (txn != null && txn.isActive()) {
            return getVersion(txn.getStartTimestamp(), txn.getStep());
        } else {
            return getVersion(UNCOMMITTED, 0);
        }
    }

    public V getVersion(final Transaction txn, final VersionCreator<T, V> creator) throws PersistitException,
            RollbackException {
        V version = getVersion(txn);
        if (version == null) {
            version = creator.createVersion(this);
            addVersion(version, txn);
        }
        return version;
    }

    /**
     * @return <code>true</code> if and only if this <code>TimelyResource</code>
     *         has no <code>Version</code> instances.
     */
    public boolean isEmpty() {
        return _first == null;
    }

    /**
     * @return Count of versions currently being managed.
     */
    public int getVersionCount() {
        int count = 0;
        for (Entry e = _first; e != null; e = e._previous) {
            count++;
        }
        return count;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("TimelyResource(");
        boolean first = true;
        for (Entry entry = _first; entry != null; entry = entry.getPrevious()) {
            if (sb.length() > 1000) {
                sb.append("...");
                break;
            }
            if (!first) {
                sb.append(',');
            } else {
                first = false;
            }
            sb.append(entry);
        }
        sb.append(')');
        return sb.toString();
    }

    synchronized void setPrimordial() {
        if (_first != null && _first.getPrevious() == null) {
            _first.setPrimordial();
        } else {
            throw new IllegalStateException("Cannot be made primordial: " + this);
        }
    }

    /**
     * Remove all obsolete <code>Version</code> instances. For each instance
     * that implements <code>PrunableVersion</code>, invoke its
     * {@link PrunableVersion#prune()} method.
     * 
     * @throws TimeoutException
     * @throws PersistitException
     */
    void prune() throws TimeoutException, PersistitException {
        final List<Entry> entriesToPrune = new ArrayList<Entry>();
        synchronized (this) {
            try {
                final TransactionIndex ti = _persistit.getTransactionIndex();

                long lastVersionHandle = Long.MAX_VALUE;
                long lastVersionTc = UNCOMMITTED;
                long uncommittedTransactionTs = 0;

                Entry newer = null;
                Entry latest = null;
                boolean isPrimordial = true;

                for (Entry entry = _first; entry != null; entry = entry.getPrevious()) {
                    boolean keepIt = false;
                    isPrimordial &= newer == null;
                    final long versionHandle = entry.getVersion();
                    final long tc = ti.commitStatus(versionHandle, UNCOMMITTED, 0);
                    if (tc >= 0) {
                        if (tc == UNCOMMITTED) {
                            final long ts = vh2ts(versionHandle);
                            if (uncommittedTransactionTs != 0 && uncommittedTransactionTs != ts) {
                                throw new IllegalStateException("Multiple uncommitted versions");
                            }
                            uncommittedTransactionTs = ts;
                            keepIt = true;
                            isPrimordial = false;
                        } else if (tc > PRIMORDIAL) {
                            final boolean hasConcurrent = ti.hasConcurrentTransaction(tc, lastVersionTc);
                            if (latest == null || hasConcurrent) {
                                keepIt = true;
                                if (latest == null) {
                                    latest = entry;
                                }
                            }
                            /*
                             * Note: versions and tcs can be the same when there
                             * are multiple steps
                             */
                            assert versionHandle < lastVersionHandle
                                    || vh2ts(versionHandle) == vh2ts(lastVersionHandle);
                            assert tc <= lastVersionTc || lastVersionTc == UNCOMMITTED;
                            lastVersionHandle = versionHandle;
                            lastVersionTc = tc;
                            if (hasConcurrent) {
                                isPrimordial = false;
                            }
                        } else if (entry.isDeleted()) {
                            keepIt = true;
                        } else {
                            isPrimordial = false;
                        }
                    } else {
                        assert tc == ABORTED;
                    }
                    if (keepIt) {
                        newer = entry;
                    } else {
                        if (!entry.isDeleted()) {
                            entriesToPrune.add(entry);
                        }
                        if (newer == null) {
                            _first = entry.getPrevious();
                        } else {
                            newer.setPrevious(entry.getPrevious());
                        }
                    }
                }
                if (_first != null && _first.isDeleted() && _first.getPrevious() == null) {
                    _first = null;
                }
                if (isPrimordial && _first != null) {
                    assert _first.getPrevious() == null;
                    _first.setPrimordial();
                }
            } catch (final InterruptedException ie) {
                throw new PersistitInterruptedException(ie);
            }
        }
        for (final Entry e : entriesToPrune) {
            e.prune();
        }
    }

    /**
     * Helper method that adds an Entry containing a Version and its timestamp
     * information. This method checks for write-write dependencies throws a
     * RollbackException if there is a conflict.
     * 
     * @param entry
     * @param txn
     * @throws PersistitException
     * @throws RollbackException
     */
    private void addVersion(final Entry entry, final Transaction txn) throws PersistitException, RollbackException {
        final TransactionIndex ti = _persistit.getTransactionIndex();
        while (true) {
            try {
                synchronized (this) {
                    if (_first != null) {
                        if (_first.getVersion() > entry.getVersion()) {
                            /*
                             * This thread lost a race to make the most recent
                             * version
                             */
                            throw new RollbackException();
                        }
                        if (txn.isActive()) {
                            for (Entry e = _first; e != null; e = e.getPrevious()) {
                                final long version = e.getVersion();
                                final long depends = ti.wwDependency(version, txn.getTransactionStatus(), 0);
                                if (depends == TIMED_OUT) {
                                    throw new WWRetryException(version);
                                }
                                if (depends != 0 && depends != ABORTED) {
                                    /*
                                     * version is from a concurrent transaction
                                     * that already committed or timed out
                                     * waiting to see. Either way, must abort.
                                     */
                                    throw new RollbackException();
                                }
                            }
                        }
                    }
                    entry.setPrevious(_first);
                    _first = entry;
                    break;
                }
            } catch (final WWRetryException re) {
                try {
                    final long depends = _persistit.getTransactionIndex().wwDependency(re.getVersionHandle(),
                            txn.getTransactionStatus(), SharedResource.DEFAULT_MAX_WAIT_TIME);
                    if (depends != 0 && depends != ABORTED) {
                        /*
                         * version is from concurrent txn that already committed
                         * or timed out waiting to see. Either way, must abort.
                         */
                        throw new RollbackException();
                    }
                } catch (final InterruptedException ie) {
                    throw new PersistitInterruptedException(ie);
                }
            } catch (final InterruptedException ie) {
                throw new PersistitInterruptedException(ie);
            }
        }
    }

    /**
     * Get the <code>Version</code> from the snapshot view specified by the
     * supplied timestamp and step.
     * 
     * @param ts
     * @param step
     * @return
     * @throws TimeoutException
     * @throws PersistitInterruptedException
     */
    V getVersion(final long ts, final int step) throws TimeoutException, PersistitInterruptedException {
        final TransactionIndex ti = _persistit.getTransactionIndex();
        try {
            /*
             * Note: not necessary to synchronize here. A concurrent transaction
             * may modify _first, but this method does not need to see that
             * version since it has not been committed. Conversely, if there is
             * some transaction that committed before this transaction's start
             * timestamp, then there is a happened-before relationship due to
             * the synchronization in transaction registration; since _first is
             * volatile, we are guaranteed to see the modification made by the
             * committed transaction.
             */
            for (Entry e = _first; e != null; e = e._previous) {
                final long commitTs = ti.commitStatus(e.getVersion(), ts, step);
                if (commitTs >= 0 && commitTs != UNCOMMITTED) {
                    return e.getResource();
                }
            }
            return null;
        } catch (final InterruptedException e) {
            throw new PersistitInterruptedException(e);
        }
    }

    /**
     * <p>
     * Holder for one instance of an object, such as a {@link Tree}, whose
     * availability is governed by visibility within a transaction.
     * </p>
     * <p>
     * An Entry has a version handle just like a version in an {@link MVV}. It
     * is invisible to any transactions that started before the start timestamp,
     * in write-write conflict with any concurrent transaction, and available to
     * any transaction that starts after the version has committed. Visibility
     * within the transaction that creates the TimelyResourceEntry is determined
     * by the relative step numbers of the current transaction and the version
     * handle. If there exists a TimelyResource instance with start and commit
     * timestamps ts1, tc1 then any attempt to add another TimelyResource will
     * fail unless ts2 > tc1.
     * </p>
     * 
     * @author peter
     */

    private class Entry {

        private long _version;
        private final V _resource;
        private volatile boolean _deleted;
        private volatile Entry _previous;

        private Entry(final long versionHandle, final V resource) {
            _version = versionHandle;
            _resource = resource;
        }

        private V getResource() {
            return _resource;
        }

        private Entry getPrevious() {
            return _previous;
        }

        private void setPrevious(final Entry tr) {
            _previous = tr;
        }

        private long getVersion() {
            return _version;
        }

        private void setPrimordial() {
            _version = PRIMORDIAL;
        }

        private void setDeleted() {
            _deleted = true;
        }

        private boolean isDeleted() {
            return _deleted;
        }

        private boolean prune() throws PersistitException {
            if (_resource instanceof PrunableVersion) {
                return ((PrunableVersion) _resource).prune();
            } else {
                return true;
            }
        }

        @Override
        public String toString() {
            String tcStatus;
            try {
                final long tc = _persistit.getTransactionIndex().commitStatus(_version, Long.MAX_VALUE, 0);
                tcStatus = TransactionStatus.tcString(tc);
            } catch (final Exception e) {
                tcStatus = e.toString();
            }
            return String.format("(tc=%s ts=%s)->%s%s", TransactionStatus.versionString(_version), tcStatus, _resource,
                    _previous != null ? "*" : "");
        }
    }

}
