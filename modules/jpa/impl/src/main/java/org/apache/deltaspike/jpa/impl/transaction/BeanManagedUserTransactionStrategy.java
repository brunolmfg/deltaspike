/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.deltaspike.jpa.impl.transaction;

import org.apache.deltaspike.core.impl.util.JndiUtils;
import org.apache.deltaspike.core.util.ExceptionUtils;
import org.apache.deltaspike.jpa.impl.transaction.context.EntityManagerEntry;

import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Alternative;
import javax.persistence.EntityManager;
import javax.persistence.EntityTransaction;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.UserTransaction;
import java.lang.annotation.Annotation;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <p>{@link org.apache.deltaspike.jpa.spi.transaction.TransactionStrategy} for using JTA (bean-managed-)transactions
 * (including XA transactions with a XA DataSource).
 * The basic features are identical to the {@link ResourceLocalTransactionStrategy} (for
 * persistent-unit-transaction-type 'RESOURCE_LOCAL' only).</p>
 */
@Dependent
@Alternative
@SuppressWarnings("UnusedDeclaration")
//TODO move to a separated ds-jta module and use @Specializes -> no additional config is needed
public class BeanManagedUserTransactionStrategy extends ResourceLocalTransactionStrategy
{
    protected static final String USER_TRANSACTION_JNDI_NAME = "java:comp/UserTransaction";

    private static final long serialVersionUID = -2432802805095533499L;

    private static final Logger LOGGER = Logger.getLogger(BeanManagedUserTransactionStrategy.class.getName());

    @Override
    protected EntityManagerEntry createEntityManagerEntry(
        EntityManager entityManager, Class<? extends Annotation> qualifier)
    {
        applyTransactionTimeout(); //needs to be done before UserTransaction#begin - TODO move this call
        return super.createEntityManagerEntry(entityManager, qualifier);
    }

    protected void applyTransactionTimeout()
    {
        Integer transactionTimeout = getDefaultTransactionTimeoutInSeconds();

        if (transactionTimeout == null)
        {
            //the default configured for the container will be used
            return;
        }

        try
        {
            UserTransaction userTransaction = resolveUserTransaction();
            userTransaction.setTransactionTimeout(transactionTimeout);
        }
        catch (SystemException e)
        {
            LOGGER.log(Level.WARNING, "UserTransaction#setTransactionTimeout failed", e);
        }
    }

    protected Integer getDefaultTransactionTimeoutInSeconds()
    {
        //override it and provide a custom value - if needed - TODO discuss a type-safe module-config for DELTASPIKE-256
        return null;
    }

    @Override
    protected EntityTransaction getTransaction(EntityManagerEntry entityManagerEntry)
    {
        return new UserTransactionAdapter();
    }

    /**
     * Needed because the {@link EntityManager} might get created outside of the {@link UserTransaction}
     * (e.g. depending on the implementation of the producer).
     * Can't be in {@link BeanManagedUserTransactionStrategy.UserTransactionAdapter#begin()}
     * because {@link ResourceLocalTransactionStrategy} needs to do
     * <pre>
     * if (!transaction.isActive())
     * {
     *     transaction.begin();
     * }
     * </pre>
     * for the {@link EntityTransaction} of every {@link EntityManager}
     * and {@link BeanManagedUserTransactionStrategy.UserTransactionAdapter#isActive()}
     * can only use the status information of the {@link UserTransaction} and therefore
     * {@link BeanManagedUserTransactionStrategy.UserTransactionAdapter#begin()}
     * will only executed once, but {@link javax.persistence.EntityManager#joinTransaction()}
     * needs to be called for every {@link EntityManager}.
     *
     * @param entityManagerEntry entry of the current entity-manager
     */

    @Override
    protected void beforeProceed(EntityManagerEntry entityManagerEntry)
    {
        entityManagerEntry.getEntityManager().joinTransaction();
    }

    protected UserTransaction resolveUserTransaction()
    {
        return JndiUtils.lookup(USER_TRANSACTION_JNDI_NAME, UserTransaction.class);
    }

    private class UserTransactionAdapter implements EntityTransaction
    {
        private final UserTransaction userTransaction;

        public UserTransactionAdapter()
        {
            this.userTransaction = resolveUserTransaction();
        }

        /**
         * Only delegate to the {@link UserTransaction} if the state of the
         * {@link UserTransaction} is {@link Status#STATUS_NO_TRANSACTION}
         * (= the status before and after a started transaction).
         */
        @Override
        public void begin()
        {
            try
            {
                //2nd check (already done by #isActive triggered by ResourceLocalTransactionStrategy directly before)
                //currently to filter STATUS_UNKNOWN - see to-do -> TODO re-visit it
                if (this.userTransaction.getStatus() == Status.STATUS_NO_TRANSACTION)
                {
                    this.userTransaction.begin();
                }
            }
            catch (Exception e)
            {
                throw ExceptionUtils.throwAsRuntimeException(e);
            }
        }

        /**
         * Only delegate to the {@link UserTransaction} if the state of the
         * {@link UserTransaction} is one of
         * <ul>
         *     <li>{@link Status#STATUS_ACTIVE}</li>
         *     <li>{@link Status#STATUS_PREPARING}</li>
         *     <li>{@link Status#STATUS_PREPARED}</li>
         * </ul>
         */
        @Override
        public void commit()
        {
            try
            {
                if (isTransactionReadyToCommit())
                {
                    this.userTransaction.commit();
                }
            }
            catch (Exception e)
            {
                throw ExceptionUtils.throwAsRuntimeException(e);
            }
        }

        /**
         * Only delegate to the {@link UserTransaction} if the state of the
         * {@link UserTransaction} is one of
         * <ul>
         *     <li>{@link Status#STATUS_ACTIVE}</li>
         *     <li>{@link Status#STATUS_PREPARING}</li>
         *     <li>{@link Status#STATUS_PREPARED}</li>
         *     <li>{@link Status#STATUS_MARKED_ROLLBACK}</li>
         *     <li>{@link Status#STATUS_COMMITTING}</li>
         * </ul>
         */
        @Override
        public void rollback()
        {
            try
            {
                if (isTransactionAllowedToRollback())
                {
                    this.userTransaction.rollback();
                }
            }
            catch (SystemException e)
            {
                throw ExceptionUtils.throwAsRuntimeException(e);
            }
        }

        @Override
        public void setRollbackOnly()
        {
            try
            {
                this.userTransaction.setRollbackOnly();
            }
            catch (SystemException e)
            {
                throw ExceptionUtils.throwAsRuntimeException(e);
            }
        }

        @Override
        public boolean getRollbackOnly()
        {
            try
            {
                return this.userTransaction.getStatus() == Status.STATUS_MARKED_ROLLBACK;
            }
            catch (SystemException e)
            {
                throw ExceptionUtils.throwAsRuntimeException(e);
            }
        }

        /**
         * @return true if the transaction has been started and not ended
         */
        @Override
        public boolean isActive()
        {
            //we can't use the status of the overall
            try
            {
                return this.userTransaction.getStatus() != Status.STATUS_NO_TRANSACTION &&
                        this.userTransaction.getStatus() != Status.STATUS_UNKNOWN; //TODO re-visit it
            }
            catch (SystemException e)
            {
                throw ExceptionUtils.throwAsRuntimeException(e);
            }
        }

        protected boolean isTransactionAllowedToRollback() throws SystemException
        {
            //if the following gets changed, it needs to be tested with different constellations
            //(normal exception, timeout,...) as well as servers
            return  this.userTransaction.getStatus() != Status.STATUS_COMMITTED &&
                    this.userTransaction.getStatus() != Status.STATUS_NO_TRANSACTION &&
                    this.userTransaction.getStatus() != Status.STATUS_UNKNOWN;
        }

        protected boolean isTransactionReadyToCommit() throws SystemException
        {
            return this.userTransaction.getStatus() == Status.STATUS_ACTIVE ||
                    this.userTransaction.getStatus() == Status.STATUS_PREPARING ||
                    this.userTransaction.getStatus() == Status.STATUS_PREPARED;
        }
    }
}
