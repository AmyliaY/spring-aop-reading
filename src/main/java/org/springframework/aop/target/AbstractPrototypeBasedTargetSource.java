/*
 * Copyright 2002-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.aop.target;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamException;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;

/**
 * Base class for dynamic {@link org.springframework.aop.TargetSource} implementations
 * that create new prototype bean instances to support a pooling or
 * new-instance-per-invocation strategy.
 *
 * <p>Such TargetSources must run in a {@link BeanFactory}, as it needs to
 * call the {@code getBean} method to create a new prototype instance.
 * Therefore, this base class extends {@link AbstractBeanFactoryBasedTargetSource}.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see org.springframework.beans.factory.BeanFactory#getBean
 * @see PrototypeTargetSource
 * @see ThreadLocalTargetSource
 * @see CommonsPoolTargetSource
 */
public abstract class AbstractPrototypeBasedTargetSource extends AbstractBeanFactoryBasedTargetSource {

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		super.setBeanFactory(beanFactory);

		// Check whether the target bean is defined as prototype.
		if (!beanFactory.isPrototype(getTargetBeanName())) {
			throw new BeanDefinitionStoreException(
					"Cannot use prototype-based TargetSource against non-prototype bean with name '" +
					getTargetBeanName() + "': instances would not be independent");
		}
	}

	/**
	 * Subclasses should call this method to create a new prototype instance.
	 * @throws BeansException if bean creation failed
	 */
	protected Object newPrototypeInstance() throws BeansException {
		if (logger.isDebugEnabled()) {
			logger.debug("Creating new instance of bean '" + getTargetBeanName() + "'");
		}
		return getBeanFactory().getBean(getTargetBeanName());
	}

	/**
	 * Subclasses should call this method to destroy an obsolete prototype instance.
	 * @param target the bean instance to destroy
	 */
	protected void destroyPrototypeInstance(Object target) {
		if (this.logger.isDebugEnabled()) {
			this.logger.debug("Destroying instance of bean '" + getTargetBeanName() + "'");
		}
		if (getBeanFactory() instanceof ConfigurableBeanFactory) {
			((ConfigurableBeanFactory) getBeanFactory()).destroyBean(getTargetBeanName(), target);
		}
		else if (target instanceof DisposableBean) {
			try {
				((DisposableBean) target).destroy();
			}
			catch (Throwable ex) {
				logger.error("Couldn't invoke destroy method of bean with name '" + getTargetBeanName() + "'", ex);
			}
		}
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		throw new NotSerializableException("A prototype-based TargetSource itself is not deserializable - " +
				"just a disconnected SingletonTargetSource is");
	}

	/**
	 * Replaces this object with a SingletonTargetSource on serialization.
	 * Protected as otherwise it won't be invoked for subclasses.
	 * (The {@code writeReplace()} method must be visible to the class
	 * being serialized.)
	 * <p>With this implementation of this method, there is no need to mark
	 * non-serializable fields in this class or subclasses as transient.
	 */
	protected Object writeReplace() throws ObjectStreamException {
		if (logger.isDebugEnabled()) {
			logger.debug("Disconnecting TargetSource [" + this + "]");
		}
		try {
			// Create disconnected SingletonTargetSource.
			return new SingletonTargetSource(getTarget());
		}
		catch (Exception ex) {
			logger.error("Cannot get target for disconnecting TargetSource [" + this + "]", ex);
			throw new NotSerializableException(
					"Cannot get target for disconnecting TargetSource [" + this + "]: " + ex);
		}
	}

}
