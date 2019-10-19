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

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;

/**
 * Spring's implementation of the AOP Alliance
 * {@link org.aopalliance.intercept.MethodInvocation} interface, implementing the extended
 * {@link org.springframework.aop.ProxyMethodInvocation} interface.
 *
 * <p>
 * Invokes the target object using reflection. Subclasses can override the
 * {@link #invokeJoinpoint()} method to change this behavior, so this is also a useful
 * base class for more specialized MethodInvocation implementations.
 *
 * <p>
 * It is possible to clone an invocation, to invoke {@link #proceed()} repeatedly (once
 * per clone), using the {@link #invocableClone()} method. It is also possible to attach
 * custom attributes to the invocation, using the {@link #setUserAttribute} /
 * {@link #getUserAttribute} methods.
 *
 * <p>
 * <b>NOTE:</b> This class is considered internal and should not be directly accessed. The
 * sole reason for it being public is compatibility with existing framework integrations
 * (e.g. Pitchfork). For any other purposes, use the {@link ProxyMethodInvocation}
 * interface instead.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Adrian Colyer
 * @see #invokeJoinpoint
 * @see #proceed
 * @see #invocableClone
 * @see #setUserAttribute
 * @see #getUserAttribute
 */
public class ReflectiveMethodInvocation implements ProxyMethodInvocation, Cloneable {

	protected final Object proxy;

	protected final Object target;

	protected final Method method;

	protected Object[] arguments;

	private final Class targetClass;

	/**
	 * Lazily initialized map of user-specific attributes for this invocation.
	 */
	private Map<String, Object> userAttributes;

	/** MethodInterceptor和InterceptorAndDynamicMethodMatcher的集合 */
	protected final List interceptorsAndDynamicMethodMatchers;

	/**
	 * Index from 0 of the current interceptor we're invoking. -1 until we invoke: then
	 * the current interceptor.
	 */
	private int currentInterceptorIndex = -1;

	/**
	 * Construct a new ReflectiveMethodInvocation with the given arguments.
	 * 
	 * @param proxy the proxy object that the invocation was made on
	 * @param target the target object to invoke
	 * @param method the method to invoke
	 * @param arguments the arguments to invoke the method with
	 * @param targetClass the target class, for MethodMatcher invocations
	 * @param interceptorsAndDynamicMethodMatchers interceptors that should be applied,
	 *        along with any InterceptorAndDynamicMethodMatchers that need evaluation at
	 *        runtime. MethodMatchers included in this struct must already have been found
	 *        to have matched as far as was possibly statically. Passing an array might be
	 *        about 10% faster, but would complicate the code. And it would work only for
	 *        static pointcuts.
	 */
	protected ReflectiveMethodInvocation(Object proxy, Object target, Method method,
			Object[] arguments, Class targetClass,
			List<Object> interceptorsAndDynamicMethodMatchers) {

		this.proxy = proxy;
		this.target = target;
		this.targetClass = targetClass;
		this.method = BridgeMethodResolver.findBridgedMethod(method);
		this.arguments = arguments;
		this.interceptorsAndDynamicMethodMatchers = interceptorsAndDynamicMethodMatchers;
	}

	public final Object getProxy() {
		return this.proxy;
	}

	public final Object getThis() {
		return this.target;
	}

	public final AccessibleObject getStaticPart() {
		return this.method;
	}

	/**
	 * Return the method invoked on the proxied interface. May or may not correspond with
	 * a method invoked on an underlying implementation of that interface.
	 */
	public final Method getMethod() {
		return this.method;
	}

	public final Object[] getArguments() {
		return (this.arguments != null ? this.arguments : new Object[0]);
	}

	public void setArguments(Object[] arguments) {
		this.arguments = arguments;
	}

	public Object proceed() throws Throwable {
		// 从拦截器链中按顺序依次调用拦截器，直到所有的拦截器调用完毕，开始调用目标方法，
		// 对目标方法的调用是在invokeJoinpoint()中通过AopUtils的invokeJoinpointUsingReflection()方法完成的
		if (this.currentInterceptorIndex == this.interceptorsAndDynamicMethodMatchers.size()
				- 1) {
			// invokeJoinpoint()直接通过AopUtils进行目标方法的调用
			return invokeJoinpoint();
		}

		// 这里沿着定义好的interceptorsAndDynamicMethodMatchers拦截器链进行处理，
		// 它是一个List，也没有定义泛型，interceptorOrInterceptionAdvice是其中的一个元素，
		Object interceptorOrInterceptionAdvice = this.interceptorsAndDynamicMethodMatchers.get(
				++this.currentInterceptorIndex);
		if (interceptorOrInterceptionAdvice instanceof InterceptorAndDynamicMethodMatcher) {
			// 这里通过拦截器的方法匹配器methodMatcher进行方法匹配，
			// 如果目标类的目标方法和配置的Pointcut匹配，那么这个增强行为advice将会被执行，
			// Pointcut定义了切面，advice定义了增强的行为
			InterceptorAndDynamicMethodMatcher dm = (InterceptorAndDynamicMethodMatcher) interceptorOrInterceptionAdvice;
			// 目标类的目标方法是否为Pointcut所定义的切面
			if (dm.methodMatcher.matches(this.method, this.targetClass, this.arguments)) {
				// 执行增强方法
				return dm.interceptor.invoke(this);
			}
			else {
				// 如果不匹配，那么process()方法会被递归调用，直到所有的拦截器都被运行过为止
				return proceed();
			}
		}
		else {
			// 如果interceptorOrInterceptionAdvice是一个MethodInterceptor
			// 则直接调用其对应的方法
			return ((MethodInterceptor) interceptorOrInterceptionAdvice).invoke(this);
		}
	}

	/**
	 * 通过反射机制完成对横切点方法的调用
	 */
	protected Object invokeJoinpoint() throws Throwable {
		return AopUtils.invokeJoinpointUsingReflection(this.target, this.method,
				this.arguments);
	}

	/**
	 * This implementation returns a shallow copy of this invocation object, including an
	 * independent copy of the original arguments array.
	 * <p>
	 * We want a shallow copy in this case: We want to use the same interceptor chain and
	 * other object references, but we want an independent value for the current
	 * interceptor index.
	 * 
	 * @see java.lang.Object#clone()
	 */
	public MethodInvocation invocableClone() {
		Object[] cloneArguments = null;
		if (this.arguments != null) {
			// Build an independent copy of the arguments array.
			cloneArguments = new Object[this.arguments.length];
			System.arraycopy(this.arguments, 0, cloneArguments, 0, this.arguments.length);
		}
		return invocableClone(cloneArguments);
	}

	/**
	 * This implementation returns a shallow copy of this invocation object, using the
	 * given arguments array for the clone.
	 * <p>
	 * We want a shallow copy in this case: We want to use the same interceptor chain and
	 * other object references, but we want an independent value for the current
	 * interceptor index.
	 * 
	 * @see java.lang.Object#clone()
	 */
	public MethodInvocation invocableClone(Object[] arguments) {
		// Force initialization of the user attributes Map,
		// for having a shared Map reference in the clone.
		if (this.userAttributes == null) {
			this.userAttributes = new HashMap<String, Object>();
		}

		// Create the MethodInvocation clone.
		try {
			ReflectiveMethodInvocation clone = (ReflectiveMethodInvocation) clone();
			clone.arguments = arguments;
			return clone;
		}
		catch (CloneNotSupportedException ex) {
			throw new IllegalStateException(
					"Should be able to clone object of type [" + getClass() + "]: " + ex);
		}
	}

	public void setUserAttribute(String key, Object value) {
		if (value != null) {
			if (this.userAttributes == null) {
				this.userAttributes = new HashMap<String, Object>();
			}
			this.userAttributes.put(key, value);
		}
		else {
			if (this.userAttributes != null) {
				this.userAttributes.remove(key);
			}
		}
	}

	public Object getUserAttribute(String key) {
		return (this.userAttributes != null ? this.userAttributes.get(key) : null);
	}

	/**
	 * Return user attributes associated with this invocation. This method provides an
	 * invocation-bound alternative to a ThreadLocal.
	 * <p>
	 * This map is initialized lazily and is not used in the AOP framework itself.
	 * 
	 * @return any user attributes associated with this invocation (never {@code null})
	 */
	public Map<String, Object> getUserAttributes() {
		if (this.userAttributes == null) {
			this.userAttributes = new HashMap<String, Object>();
		}
		return this.userAttributes;
	}

	@Override
	public String toString() {
		// Don't do toString on target, it may be proxied.
		StringBuilder sb = new StringBuilder("ReflectiveMethodInvocation: ");
		sb.append(this.method).append("; ");
		if (this.target == null) {
			sb.append("target is null");
		}
		else {
			sb.append("target is of class [").append(
					this.target.getClass().getName()).append(']');
		}
		return sb.toString();
	}

}
