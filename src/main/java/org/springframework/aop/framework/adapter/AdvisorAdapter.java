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

package org.springframework.aop.framework.adapter;

import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;

import org.springframework.aop.Advisor;

/**
 * Advice 适配器的顶级接口
 * @author Rod Johnson
 */
public interface AdvisorAdapter {

	/**
	 * 此适配器是否能适配 给定的 advice 对象
	 */
	boolean supportsAdvice(Advice advice);

	/**
	 * 获取传入的 advisor 中的 Advice 对象，将其适配成 MethodInterceptor 对象
	 */
	MethodInterceptor getInterceptor(Advisor advisor);
}
