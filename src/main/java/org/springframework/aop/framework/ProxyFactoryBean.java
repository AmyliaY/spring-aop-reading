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

package org.springframework.aop.framework;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.Interceptor;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aop.Advisor;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.adapter.AdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry;
import org.springframework.aop.framework.adapter.UnknownAdviceTypeException;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.FactoryBeanNotInitializedException;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.core.OrderComparator;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;

/**
 * {@link org.springframework.beans.factory.FactoryBean} implementation that builds an
 * AOP proxy based on beans in Spring {@link org.springframework.beans.factory.BeanFactory}.
 *
 * <p>{@link org.aopalliance.intercept.MethodInterceptor MethodInterceptors} and
 * {@link org.springframework.aop.Advisor Advisors} are identified by a list of bean
 * names in the current bean factory, specified through the "interceptorNames" property.
 * The last entry in the list can be the name of a target bean or a
 * {@link org.springframework.aop.TargetSource}; however, it is normally preferable
 * to use the "targetName"/"target"/"targetSource" properties instead.
 *
 * <p>Global interceptors and advisors can be added at the factory level. The specified
 * ones are expanded in an interceptor list where an "xxx*" entry is included in the
 * list, matching the given prefix with the bean names (e.g. "global*" would match
 * both "globalBean1" and "globalBean2", "*" all defined interceptors). The matching
 * interceptors get applied according to their returned order value, if they implement
 * the {@link org.springframework.core.Ordered} interface.
 *
 * <p>Creates a JDK proxy when proxy interfaces are given, and a CGLIB proxy for the
 * actual target class if not. Note that the latter will only work if the target class
 * does not have final methods, as a dynamic subclass will be created at runtime.
 *
 * <p>It's possible to cast a proxy obtained from this factory to {@link Advised},
 * or to obtain the ProxyFactoryBean reference and programmatically manipulate it.
 * This won't work for existing prototype references, which are independent. However,
 * it will work for prototypes subsequently obtained from the factory. Changes to
 * interception will work immediately on singletons (including existing references).
 * However, to change interfaces or target it's necessary to obtain a new instance
 * from the factory. This means that singleton instances obtained from the factory
 * do not have the same object identity. However, they do have the same interceptors
 * and target, and changing any reference will change all objects.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @see #setInterceptorNames
 * @see #setProxyInterfaces
 * @see org.aopalliance.intercept.MethodInterceptor
 * @see org.springframework.aop.Advisor
 * @see Advised
 */
@SuppressWarnings("serial")
public class ProxyFactoryBean extends ProxyCreatorSupport
		implements FactoryBean<Object>, BeanClassLoaderAware, BeanFactoryAware {

	/**
	 * This suffix in a value in an interceptor list indicates to expand globals.
	 */
	public static final String GLOBAL_SUFFIX = "*";


	protected final Log logger = LogFactory.getLog(getClass());

	private String[] interceptorNames;

	private String targetName;

	private boolean autodetectInterfaces = true;

	private boolean singleton = true;

	private AdvisorAdapterRegistry advisorAdapterRegistry = GlobalAdvisorAdapterRegistry.getInstance();

	private boolean freezeProxy = false;

	private transient ClassLoader proxyClassLoader = ClassUtils.getDefaultClassLoader();

	private transient boolean classLoaderConfigured = false;

	//实现了BeanFactoryAware接口，持有了对BeanFactory的引用，实现了BeanFactoryAware接口的setBeanFactory()方法
	private transient BeanFactory beanFactory;

	/** Whether the advisor chain has already been initialized */
	private boolean advisorChainInitialized = false;

	/** If this is a singleton, the cached singleton proxy instance */
	private Object singletonInstance;


	/**
	 * Set the names of the interfaces we're proxying. If no interface
	 * is given, a CGLIB for the actual class will be created.
	 * <p>This is essentially equivalent to the "setInterfaces" method,
	 * but mirrors TransactionProxyFactoryBean's "setProxyInterfaces".
	 * @see #setInterfaces
	 * @see AbstractSingletonProxyFactoryBean#setProxyInterfaces
	 */
	public void setProxyInterfaces(Class[] proxyInterfaces) throws ClassNotFoundException {
		setInterfaces(proxyInterfaces);
	}

	/**
	 * Set the list of Advice/Advisor bean names. This must always be set
	 * to use this factory bean in a bean factory.
	 * <p>The referenced beans should be of type Interceptor, Advisor or Advice
	 * The last entry in the list can be the name of any bean in the factory.
	 * If it's neither an Advice nor an Advisor, a new SingletonTargetSource
	 * is added to wrap it. Such a target bean cannot be used if the "target"
	 * or "targetSource" or "targetName" property is set, in which case the
	 * "interceptorNames" array must contain only Advice/Advisor bean names.
	 * <p><b>NOTE: Specifying a target bean as final name in the "interceptorNames"
	 * list is deprecated and will be removed in a future Spring version.</b>
	 * Use the {@link #setTargetName "targetName"} property instead.
	 * @see org.aopalliance.intercept.MethodInterceptor
	 * @see org.springframework.aop.Advisor
	 * @see org.aopalliance.aop.Advice
	 * @see org.springframework.aop.target.SingletonTargetSource
	 */
	public void setInterceptorNames(String[] interceptorNames) {
		this.interceptorNames = interceptorNames;
	}

	/**
	 * Set the name of the target bean. This is an alternative to specifying
	 * the target name at the end of the "interceptorNames" array.
	 * <p>You can also specify a target object or a TargetSource object
	 * directly, via the "target"/"targetSource" property, respectively.
	 * @see #setInterceptorNames(String[])
	 * @see #setTarget(Object)
	 * @see #setTargetSource(org.springframework.aop.TargetSource)
	 */
	public void setTargetName(String targetName) {
		this.targetName = targetName;
	}

	/**
	 * Set whether to autodetect proxy interfaces if none specified.
	 * <p>Default is "true". Turn this flag off to create a CGLIB
	 * proxy for the full target class if no interfaces specified.
	 * @see #setProxyTargetClass
	 */
	public void setAutodetectInterfaces(boolean autodetectInterfaces) {
		this.autodetectInterfaces = autodetectInterfaces;
	}

	/**
	 * Set the value of the singleton property. Governs whether this factory
	 * should always return the same proxy instance (which implies the same target)
	 * or whether it should return a new prototype instance, which implies that
	 * the target and interceptors may be new instances also, if they are obtained
	 * from prototype bean definitions. This allows for fine control of
	 * independence/uniqueness in the object graph.
	 */
	public void setSingleton(boolean singleton) {
		this.singleton = singleton;
	}

	/**
	 * Specify the AdvisorAdapterRegistry to use.
	 * Default is the global AdvisorAdapterRegistry.
	 * @see org.springframework.aop.framework.adapter.GlobalAdvisorAdapterRegistry
	 */
	public void setAdvisorAdapterRegistry(AdvisorAdapterRegistry advisorAdapterRegistry) {
		this.advisorAdapterRegistry = advisorAdapterRegistry;
	}

	@Override
	public void setFrozen(boolean frozen) {
		this.freezeProxy = frozen;
	}

	/**
	 * Set the ClassLoader to generate the proxy class in.
	 * <p>Default is the bean ClassLoader, i.e. the ClassLoader used by the
	 * containing BeanFactory for loading all bean classes. This can be
	 * overridden here for specific proxies.
	 */
	public void setProxyClassLoader(ClassLoader classLoader) {
		this.proxyClassLoader = classLoader;
		this.classLoaderConfigured = (classLoader != null);
	}

	public void setBeanClassLoader(ClassLoader classLoader) {
		if (!this.classLoaderConfigured) {
			this.proxyClassLoader = classLoader;
		}
	}

	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
		checkInterceptorNames();
	}


	/**
	 * 返回一个代理对象，当用户从FactoryBean中获取bean时调用，
	 * 创建此工厂要返回的AOP代理的实例，该实例将作为一个单例被缓存
	 */
	public Object getObject() throws BeansException {
		//初始化通知器链
		initializeAdvisorChain();
		//这里对Singleton和prototype的类型进行区分，生成对应的proxy
		if (isSingleton()) {
			return getSingletonInstance();
		}
		else {
			if (this.targetName == null) {
				logger.warn("Using non-singleton proxies with singleton targets is often undesirable. " +
						"Enable prototype proxies by setting the 'targetName' property.");
			}
			return newPrototypeInstance();
		}
	}

	/**
	 * Return the type of the proxy. Will check the singleton instance if
	 * already created, else fall back to the proxy interface (in case of just
	 * a single one), the target bean type, or the TargetSource's target class.
	 * @see org.springframework.aop.TargetSource#getTargetClass
	 */
	public Class<?> getObjectType() {
		synchronized (this) {
			if (this.singletonInstance != null) {
				return this.singletonInstance.getClass();
			}
		}
		Class[] ifcs = getProxiedInterfaces();
		if (ifcs.length == 1) {
			return ifcs[0];
		}
		else if (ifcs.length > 1) {
			return createCompositeInterface(ifcs);
		}
		else if (this.targetName != null && this.beanFactory != null) {
			return this.beanFactory.getType(this.targetName);
		}
		else {
			return getTargetClass();
		}
	}

	public boolean isSingleton() {
		return this.singleton;
	}


	/**
	 * Create a composite interface Class for the given interfaces,
	 * implementing the given interfaces in one single Class.
	 * <p>The default implementation builds a JDK proxy class for the
	 * given interfaces.
	 * @param interfaces the interfaces to merge
	 * @return the merged interface as Class
	 * @see java.lang.reflect.Proxy#getProxyClass
	 */
	protected Class createCompositeInterface(Class[] interfaces) {
		return ClassUtils.createCompositeInterface(interfaces, this.proxyClassLoader);
	}

	/**
	 * 返回此类代理对象的单例实例，如果尚未创建该实例，则使用单例模式的懒汉式 创建它
	 */
	private synchronized Object getSingletonInstance() {
		if (this.singletonInstance == null) {
			this.targetSource = freshTargetSource();
			if (this.autodetectInterfaces && getProxiedInterfaces().length == 0 && !isProxyTargetClass()) {
				//根据AOP框架来判断需要代理的接口
				Class targetClass = getTargetClass();
				if (targetClass == null) {
					throw new FactoryBeanNotInitializedException("Cannot determine target class for proxy");
				}
				//设置代理对象的接口
				setInterfaces(ClassUtils.getAllInterfacesForClass(targetClass, this.proxyClassLoader));
			}
			super.setFrozen(this.freezeProxy);
			//这里会通过AopProxy来得到代理对象
			this.singletonInstance = getProxy(createAopProxy());
		}
		return this.singletonInstance;
	}
	
	/**
	 * 通过createAopProxy()方法返回的aopProxy获取代理对象
	 */
	protected Object getProxy(AopProxy aopProxy) {
		return aopProxy.getProxy(this.proxyClassLoader);
	}

	/**
	 * Create a new prototype instance of this class's created proxy object,
	 * backed by an independent AdvisedSupport configuration.
	 * @return a totally independent proxy, whose advice we may manipulate in isolation
	 */
	private synchronized Object newPrototypeInstance() {
		// In the case of a prototype, we need to give the proxy
		// an independent instance of the configuration.
		// In this case, no proxy will have an instance of this object's configuration,
		// but will have an independent copy.
		if (logger.isTraceEnabled()) {
			logger.trace("Creating copy of prototype ProxyFactoryBean config: " + this);
		}

		ProxyCreatorSupport copy = new ProxyCreatorSupport(getAopProxyFactory());
		// The copy needs a fresh advisor chain, and a fresh TargetSource.
		TargetSource targetSource = freshTargetSource();
		copy.copyConfigurationFrom(this, targetSource, freshAdvisorChain());
		if (this.autodetectInterfaces && getProxiedInterfaces().length == 0 && !isProxyTargetClass()) {
			// Rely on AOP infrastructure to tell us what interfaces to proxy.
			copy.setInterfaces(
					ClassUtils.getAllInterfacesForClass(targetSource.getTargetClass(), this.proxyClassLoader));
		}
		copy.setFrozen(this.freezeProxy);

		if (logger.isTraceEnabled()) {
			logger.trace("Using ProxyCreatorSupport copy: " + copy);
		}
		return getProxy(copy.createAopProxy());
	}

	/**
	 * Check the interceptorNames list whether it contains a target name as final element.
	 * If found, remove the final name from the list and set it as targetName.
	 */
	private void checkInterceptorNames() {
		if (!ObjectUtils.isEmpty(this.interceptorNames)) {
			String finalName = this.interceptorNames[this.interceptorNames.length - 1];
			if (this.targetName == null && this.targetSource == EMPTY_TARGET_SOURCE) {
				// The last name in the chain may be an Advisor/Advice or a target/TargetSource.
				// Unfortunately we don't know; we must look at type of the bean.
				if (!finalName.endsWith(GLOBAL_SUFFIX) && !isNamedBeanAnAdvisorOrAdvice(finalName)) {
					// The target isn't an interceptor.
					this.targetName = finalName;
					if (logger.isDebugEnabled()) {
						logger.debug("Bean with name '" + finalName + "' concluding interceptor chain " +
								"is not an advisor class: treating it as a target or TargetSource");
					}
					String[] newNames = new String[this.interceptorNames.length - 1];
					System.arraycopy(this.interceptorNames, 0, newNames, 0, newNames.length);
					this.interceptorNames = newNames;
				}
			}
		}
	}

	/**
	 * Look at bean factory metadata to work out whether this bean name,
	 * which concludes the interceptorNames list, is an Advisor or Advice,
	 * or may be a target.
	 * @param beanName bean name to check
	 * @return {@code true} if it's an Advisor or Advice
	 */
	private boolean isNamedBeanAnAdvisorOrAdvice(String beanName) {
		Class namedBeanClass = this.beanFactory.getType(beanName);
		if (namedBeanClass != null) {
			return (Advisor.class.isAssignableFrom(namedBeanClass) || Advice.class.isAssignableFrom(namedBeanClass));
		}
		// Treat it as an target bean if we can't tell.
		if (logger.isDebugEnabled()) {
			logger.debug("Could not determine type of bean with name '" + beanName +
					"' - assuming it is neither an Advisor nor an Advice");
		}
		return false;
	}

	/**
	 * 初始化Advisor链，可以发现，其中有通过对IoC容器的getBean()方法的调用来获取配置好的advisor通知器
	 */
	private synchronized void initializeAdvisorChain() throws AopConfigException, BeansException {
		//如果通知器链已经完成初始化，则直接返回
		if (this.advisorChainInitialized) {
			return;
		}

		if (!ObjectUtils.isEmpty(this.interceptorNames)) {
			if (this.beanFactory == null) {
				throw new IllegalStateException("No BeanFactory available anymore (probably due to serialization) " +
						"- cannot resolve interceptor names " + Arrays.asList(this.interceptorNames));
			}

			if (this.interceptorNames[this.interceptorNames.length - 1].endsWith(GLOBAL_SUFFIX) &&
					this.targetName == null && this.targetSource == EMPTY_TARGET_SOURCE) {
				throw new AopConfigException("Target required after globals");
			}

			//这里添加了Advisor链的调用，下面的interceptorNames是在配置文件中
			//通过interceptorNames进行配置的。由于每一个Advisor都是被配置为bean的，
			//所以通过遍历interceptorNames得到的name，其实就是bean的id，通过这个name（id）
			//我们就可以从IoC容器中获取对应的实例化bean
			for (String name : this.interceptorNames) {
				if (logger.isTraceEnabled()) {
					logger.trace("Configuring advisor or advice '" + name + "'");
				}

				if (name.endsWith(GLOBAL_SUFFIX)) {
					if (!(this.beanFactory instanceof ListableBeanFactory)) {
						throw new AopConfigException(
								"Can only use global advisors or interceptors with a ListableBeanFactory");
					}
					addGlobalAdvisor((ListableBeanFactory) this.beanFactory,
							name.substring(0, name.length() - GLOBAL_SUFFIX.length()));
				}

				else {
					//对当前的factoryBean进行类型判断，是属于单例bean还是原型bean
					Object advice;
					if (this.singleton || this.beanFactory.isSingleton(name)) {
						//advisor在文件中配置为bean，所以这里通过beanFactory的getBean()方法
						//获取advisor，这个name是从interceptorNames中获取的
						advice = this.beanFactory.getBean(name);
					}
					else {
						//如果是原型bean
						advice = new PrototypePlaceholderAdvisor(name);
					}
					//把从IoC容器中获取的advice放进advisors拦截器链，这个拦截器链是由ProxyFactoryBean
					//的父类AdvisedSupport持有的
					addAdvisorOnChainCreation(advice, name);
				}
			}
		}

		this.advisorChainInitialized = true;
	}


	/**
	 * Return an independent advisor chain.
	 * We need to do this every time a new prototype instance is returned,
	 * to return distinct instances of prototype Advisors and Advices.
	 */
	private List<Advisor> freshAdvisorChain() {
		Advisor[] advisors = getAdvisors();
		List<Advisor> freshAdvisors = new ArrayList<Advisor>(advisors.length);
		for (Advisor advisor : advisors) {
			if (advisor instanceof PrototypePlaceholderAdvisor) {
				PrototypePlaceholderAdvisor pa = (PrototypePlaceholderAdvisor) advisor;
				if (logger.isDebugEnabled()) {
					logger.debug("Refreshing bean named '" + pa.getBeanName() + "'");
				}
				// Replace the placeholder with a fresh prototype instance resulting
				// from a getBean() lookup
				if (this.beanFactory == null) {
					throw new IllegalStateException("No BeanFactory available anymore (probably due to serialization) " +
							"- cannot resolve prototype advisor '" + pa.getBeanName() + "'");
				}
				Object bean = this.beanFactory.getBean(pa.getBeanName());
				Advisor refreshedAdvisor = namedBeanToAdvisor(bean);
				freshAdvisors.add(refreshedAdvisor);
			}
			else {
				// Add the shared instance.
				freshAdvisors.add(advisor);
			}
		}
		return freshAdvisors;
	}

	/**
	 * Add all global interceptors and pointcuts.
	 */
	private void addGlobalAdvisor(ListableBeanFactory beanFactory, String prefix) {
		String[] globalAdvisorNames =
				BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, Advisor.class);
		String[] globalInterceptorNames =
				BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, Interceptor.class);
		List<Object> beans = new ArrayList<Object>(globalAdvisorNames.length + globalInterceptorNames.length);
		Map<Object, String> names = new HashMap<Object, String>(beans.size());
		for (String name : globalAdvisorNames) {
			Object bean = beanFactory.getBean(name);
			beans.add(bean);
			names.put(bean, name);
		}
		for (String name : globalInterceptorNames) {
			Object bean = beanFactory.getBean(name);
			beans.add(bean);
			names.put(bean, name);
		}
		OrderComparator.sort(beans);
		for (Object bean : beans) {
			String name = names.get(bean);
			if (name.startsWith(prefix)) {
				addAdvisorOnChainCreation(bean, name);
			}
		}
	}

	/**
	 * Invoked when advice chain is created.
	 * <p>Add the given advice, advisor or object to the interceptor list.
	 * Because of these three possibilities, we can't type the signature
	 * more strongly.
	 * @param next advice, advisor or target object
	 * @param name bean name from which we obtained this object in our owning
	 * bean factory
	 */
	private void addAdvisorOnChainCreation(Object next, String name) {
		// We need to convert to an Advisor if necessary so that our source reference
		// matches what we find from superclass interceptors.
		Advisor advisor = namedBeanToAdvisor(next);
		if (logger.isTraceEnabled()) {
			logger.trace("Adding advisor with name '" + name + "'");
		}
		addAdvisor(advisor);
	}

	/**
	 * Return a TargetSource to use when creating a proxy. If the target was not
	 * specified at the end of the interceptorNames list, the TargetSource will be
	 * this class's TargetSource member. Otherwise, we get the target bean and wrap
	 * it in a TargetSource if necessary.
	 */
	private TargetSource freshTargetSource() {
		if (this.targetName == null) {
			if (logger.isTraceEnabled()) {
				logger.trace("Not refreshing target: Bean name not specified in 'interceptorNames'.");
			}
			return this.targetSource;
		}
		else {
			if (this.beanFactory == null) {
				throw new IllegalStateException("No BeanFactory available anymore (probably due to serialization) " +
						"- cannot resolve target with name '" + this.targetName + "'");
			}
			if (logger.isDebugEnabled()) {
				logger.debug("Refreshing target with name '" + this.targetName + "'");
			}
			Object target = this.beanFactory.getBean(this.targetName);
			return (target instanceof TargetSource ? (TargetSource) target : new SingletonTargetSource(target));
		}
	}

	/**
	 * Convert the following object sourced from calling getBean() on a name in the
	 * interceptorNames array to an Advisor or TargetSource.
	 */
	private Advisor namedBeanToAdvisor(Object next) {
		try {
			return this.advisorAdapterRegistry.wrap(next);
		}
		catch (UnknownAdviceTypeException ex) {
			// We expected this to be an Advisor or Advice,
			// but it wasn't. This is a configuration error.
			throw new AopConfigException("Unknown advisor type " + next.getClass() +
					"; Can only include Advisor or Advice type beans in interceptorNames chain except for last entry," +
					"which may also be target or TargetSource", ex);
		}
	}

	/**
	 * Blow away and recache singleton on an advice change.
	 */
	@Override
	protected void adviceChanged() {
		super.adviceChanged();
		if (this.singleton) {
			logger.debug("Advice has changed; recaching singleton instance");
			synchronized (this) {
				this.singletonInstance = null;
			}
		}
	}


	//---------------------------------------------------------------------
	// Serialization support
	//---------------------------------------------------------------------

	private void readObject(ObjectInputStream ois) throws IOException, ClassNotFoundException {
		// Rely on default serialization; just initialize state after deserialization.
		ois.defaultReadObject();

		// Initialize transient fields.
		this.proxyClassLoader = ClassUtils.getDefaultClassLoader();
	}


	/**
	 * Used in the interceptor chain where we need to replace a bean with a prototype
	 * on creating a proxy.
	 */
	private static class PrototypePlaceholderAdvisor implements Advisor, Serializable {

		private final String beanName;

		private final String message;

		public PrototypePlaceholderAdvisor(String beanName) {
			this.beanName = beanName;
			this.message = "Placeholder for prototype Advisor/Advice with bean name '" + beanName + "'";
		}

		public String getBeanName() {
			return beanName;
		}

		public Advice getAdvice() {
			throw new UnsupportedOperationException("Cannot invoke methods: " + this.message);
		}

		public boolean isPerInstance() {
			throw new UnsupportedOperationException("Cannot invoke methods: " + this.message);
		}

		@Override
		public String toString() {
			return this.message;
		}
	}

}
