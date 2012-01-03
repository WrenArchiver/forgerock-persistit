/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.persistit.newtxn;

import java.util.concurrent.Semaphore;

import com.persistit.Transaction;

/**
 * A TxnStatus record holds ephemeral information about a the status of a
 * transaction. A pool of TxnStatus objects is used to represent the status of
 * concurrently executing transactions, plus those that recently committed or
 * aborted but have not yet been resolved. A TxnStatus is resolved and can be
 * reused, after (a) its transaction has committed and the commit floor has been
 * increased to at least the value of tp, or (b) it has been aborted and there
 * are no more value versions created by the aborted transaction in existence.
 * 
 * @author peter
 * 
 */
public class TxnStatus {

    /**
     * Address of TS record on journal
     */
    long journalAddress;
    /**
     * Start timestamp of the associated transaction
     */
    long ts;
    /**
     * Proposal timestamp
     */
    long tc;

    /**
     * The associated Transaction (maybe does not even exist)
     */
    Transaction transaction;
    /**
     * Another TxnStatus in the same hash bucket, or null if there is none
     */
    TxnStatus next;
    /**
     * A Semaphore on which to wait for resolution of a ww-dependency.
     */
    Semaphore semaphore;
    
    /**
     * TxnStatus of another transaction this transaction is waiting for.
     */
    TxnStatus wwDependsOn;
    
    /**
     * Count of value versions added to MVV instances - used to reference
     * count values on aborts.
     */
    
    long valueVersionCount;
}