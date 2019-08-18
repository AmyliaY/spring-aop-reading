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

package org.springframework.aop.support;

import org.aopalliance.aop.Advice;

/**
 * Abstract generic PointcutAdvisor that allows for any Advice to be configured.
 *
 * @author Juergen Hoeller
 * @since 2.0
 * @see #setAdvice
 * @see DefaultPointcutAdvisor
 */
@SuppressWarnings("serial")
public abstract class AbstractGenericPointcutAdvisor extends AbstractPointcutAdvisor {

	//在抽象类中持有Advice的引用，在具体的子类中持有对Pointcut的引用
	private Advice advice;

	public void setAdvice(Advice advice) {
		this.advice = advice;
	}

	public Advice getAdvice() {
		return this.advice;
	}

	@Override
	public String toString() {
		return getClass().getName() + ": advice [" + getAdvice() + "]";
	}

}
