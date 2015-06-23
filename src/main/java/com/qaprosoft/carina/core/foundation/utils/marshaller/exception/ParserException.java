/*
 * Copyright 2013-2015 QAPROSOFT (http://qaprosoft.com/).
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
package com.qaprosoft.carina.core.foundation.utils.marshaller.exception;

public class ParserException extends RuntimeException {

	/**
	 * 
	 */
	private static final long serialVersionUID = 2740305459532726278L;

	public ParserException(String message) {
		super(message);
	}

	public ParserException(String message, Throwable e) {
		super(message, e);
	}

}
