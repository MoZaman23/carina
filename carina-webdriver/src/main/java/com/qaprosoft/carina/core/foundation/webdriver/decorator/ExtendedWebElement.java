/*******************************************************************************
 * Copyright 2013-2018 QaProSoft (http://www.qaprosoft.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.qaprosoft.carina.core.foundation.webdriver.decorator;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.hamcrest.BaseMatcher;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.InvalidElementStateException;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.Point;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.StaleElementReferenceException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.interactions.internal.Locatable;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.RemoteWebElement;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.support.events.EventFiringWebDriver;
import org.openqa.selenium.support.ui.ExpectedCondition;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.Select;
import org.openqa.selenium.support.ui.Wait;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.testng.Assert;

import com.qaprosoft.carina.core.foundation.commons.SpecialKeywords;
import com.qaprosoft.carina.core.foundation.crypto.CryptoTool;
import com.qaprosoft.carina.core.foundation.performance.ACTION_NAME;
import com.qaprosoft.carina.core.foundation.performance.Timer;
import com.qaprosoft.carina.core.foundation.utils.Configuration;
import com.qaprosoft.carina.core.foundation.utils.Configuration.Parameter;
import com.qaprosoft.carina.core.foundation.utils.Messager;
import com.qaprosoft.carina.core.foundation.utils.R;
import com.qaprosoft.carina.core.foundation.utils.common.CommonUtils;
import com.qaprosoft.carina.core.foundation.utils.mobile.MobileUtils;
import com.qaprosoft.carina.core.foundation.webdriver.DriverPool;
import com.qaprosoft.carina.core.foundation.webdriver.listener.DriverListener;
import com.qaprosoft.carina.core.foundation.webdriver.locator.ExtendedElementLocator;

import io.appium.java_client.MobileBy;

// TODO: [VD] removed deprecated constructor and DriverPool import
public class ExtendedWebElement {
    private static final Logger LOGGER = Logger.getLogger(ExtendedWebElement.class);

    private static final long EXPLICIT_TIMEOUT = Configuration.getLong(Parameter.EXPLICIT_TIMEOUT);

    private static final long RETRY_TIME = Configuration.getLong(Parameter.RETRY_INTERVAL);

    private static Wait<WebDriver> wait;
    
    private WebDriver driver;
    
    private CryptoTool cryptoTool = new CryptoTool(Configuration.get(Parameter.CRYPTO_KEY_PATH));

    private static Pattern CRYPTO_PATTERN = Pattern.compile(SpecialKeywords.CRYPT);

    private WebElement element;
    private String name;
    private By by;

    
    //TODO: remove deprecated constructors and combined rest of functionality without code duplicates
    @Deprecated
    public ExtendedWebElement(WebElement element, String name, WebDriver driver) {
        this(element, name);
    }

    @Deprecated
    public ExtendedWebElement(WebElement element, String name, By by, WebDriver driver) {
        this(element, name);
        this.by = by;
    }

    @Deprecated
    public ExtendedWebElement(WebElement element, WebDriver driver) {
    	this(element);
    }
    
    public ExtendedWebElement(WebElement element, String name, By by) {
        this(element, name);
        this.by = by;
    }

    public ExtendedWebElement(By by, String name) {
    	this.by = by;
    	this.name = name;
    	this.element = null;
    	
    }
    
    public ExtendedWebElement(By by, String name, WebDriver driver) {
    	this.by = by;
    	this.name = name;
    	this.driver = driver;
    	
    }
    
    public ExtendedWebElement(WebElement element, String name) {
    	this(element);
    	this.name = name;
    }
    
    public ExtendedWebElement(WebElement element) {
        this.element = element;
        
        //read searchContext from not null elements only
        if (element == null) {
        	// it seems like we have to specify WebElement or By annotation! Add verification that By is valid in this case!
        	if (getBy() == null) {
				try {
					throw new RuntimeException("review stacktrace to analyze why tempBy is not populated correctly via reflection!");
				} catch (Throwable thr) {
					thr.printStackTrace();
				}
        	}
        	return;
        }

		try {
			Field locatorField, searchContextField, byContextField = null;
			SearchContext searchContext = null;

			if (element.getClass().toString().contains("EventFiringWebDriver$EventFiringWebElement")) {
				// reuse reflection to get internal fields
				locatorField = element.getClass().getDeclaredField("underlyingElement");
				locatorField.setAccessible(true);
				element = (RemoteWebElement) locatorField.get(element);
			}

			if (element instanceof RemoteWebElement) {
				searchContext = ((RemoteWebElement) element).getWrappedDriver();
			} else if (element instanceof Proxy) {
				InvocationHandler innerProxy = Proxy.getInvocationHandler(((Proxy) element));

				locatorField = innerProxy.getClass().getDeclaredField("locator");
				locatorField.setAccessible(true);

				ExtendedElementLocator locator = (ExtendedElementLocator) locatorField.get(innerProxy);

				searchContextField = locator.getClass().getDeclaredField("searchContext");
				searchContextField.setAccessible(true);
				searchContext = (SearchContext) searchContextField.get(locator);

				byContextField = locator.getClass().getDeclaredField("by");
				byContextField.setAccessible(true);
				this.by = (By) byContextField.get(locator);

				if (searchContext instanceof Proxy) {
					innerProxy = Proxy.getInvocationHandler(((Proxy) searchContext));

					locatorField = innerProxy.getClass().getDeclaredField("locator");
					locatorField.setAccessible(true);

					locator = (ExtendedElementLocator) locatorField.get(innerProxy);

					searchContextField = locator.getClass().getDeclaredField("searchContext");
					searchContextField.setAccessible(true);
					searchContext = (SearchContext) searchContextField.get(locator);
				}
			}

			if (searchContext instanceof EventFiringWebDriver) {
				EventFiringWebDriver eventFirDriver = (EventFiringWebDriver) searchContext;
				this.driver = eventFirDriver.getWrappedDriver();
				return;
			}

			if (searchContext.getClass().toString().contains("EventFiringWebDriver$EventFiringWebElement")) {
				// reuse reflection to get internal fields
				locatorField = searchContext.getClass().getDeclaredField("underlyingElement");
				locatorField.setAccessible(true);
				searchContext = (RemoteWebElement) locatorField.get(searchContext);
			}

			if (searchContext instanceof RemoteWebElement) {
				searchContext = ((RemoteWebElement) searchContext).getWrappedDriver();
			}
			if (searchContext != null && searchContext instanceof RemoteWebDriver) {
				SessionId sessionId = ((RemoteWebDriver) searchContext).getSessionId();
				this.driver = DriverPool.getDriver(sessionId);
			} else {
				LOGGER.error(searchContext);
			}
		} catch (NoSuchFieldException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (ClassCastException e) {
			e.printStackTrace();
		} catch (Throwable thr) {
			thr.printStackTrace();
			LOGGER.error("Unable to get driver and/or by for " + getNameWithLocator(), thr);
		}
    }

    public WebElement getElement() {
    	//TODO: think about legacy selenium call support as a feature
    	element = refindElement(getBy(), 1);
    	return element;
    }
    
    private WebElement getCachedElement() {
        if (element == null) {
        	//TODO: why 1 sec?
            element = findElement(1);
        }
        return element;
    }

    /**
     * Check that element present within specified timeout.
     *
     * @param timeout - timeout.
     * @return element existence status.
     */
    public boolean isPresent(long timeout) {
    	return isPresent(getBy(), timeout);
    }
    
    /**
     * Check that element with By present within specified timeout.
     *
     * @param by - By.
     * @param timeout - timeout.
     * @return element existence status.
     */
    public boolean isPresent(By by, long timeout) {
		return waitUntil(ExpectedConditions.presenceOfElementLocated(by), timeout);
	}
	
	
    /**
     * Wait until any condition happens.
     *
     * @param condition - ExpectedCondition.
     * @param timeout - timeout.
     * @return true if condition happen.
     */
	private boolean waitUntil(ExpectedCondition<?> condition, long timeout) {
		boolean result;
		final WebDriver drv = getDriver();
		Timer.start(ACTION_NAME.WAIT);
		wait = new WebDriverWait(drv, timeout, RETRY_TIME);
		try {
			LOGGER.debug("waitUntil: starting..." + getNameWithLocator() + "; condition: " + condition.toString());
			wait.until(condition);
			result = true;
			LOGGER.debug("waitUntil: finished true..." + getNameWithLocator());
		} catch (NoSuchElementException | TimeoutException e) {
			// don't write exception even in debug mode
			LOGGER.debug("waitUntil: NoSuchElementException | TimeoutException e..." + getNameWithLocator());
			result = false;
		} catch (Exception e) {
			LOGGER.error("waitUntil: " + getNameWithLocator(), e);
			result = false;
		}
		Timer.stop(ACTION_NAME.WAIT);
		return result;
	}

    private WebElement findElement(long timeout) {
        if (element != null) {
            return element;
        }
        
        if (isPresent(timeout)) {
        	element = getDriver().findElement(by);
        } else {
        	throw new RuntimeException("Unable to find dynamic element using By: " + by.toString());
        }

        return element;
    }
    
    private WebElement refindElement(By by, long timeout) {
        LOGGER.info("explicitly find element using by annotation: " + by);
        if (isPresent(timeout)) {
        	element = getDriver().findElement(by);
        } else {
        	throw new RuntimeException("Unable to find dynamic element using By: " + by.toString());
        }

        return element;
    }

    public void setElement(WebElement element) {
        this.element = element;
    }

    public String getName() {
        return name != null ? name : String.format(" (%s)", by);
    }

    public String getNameWithLocator() {
        return by != null ? name + String.format(" (%s)", by) : name + " (n/a)";
    }

    public void setName(String name) {
        this.name = name;
    }
    
    /**
     * Get element By.
     *
     * @return By by
     */
    public By getBy() {
        return by;
    }

    public void setBy(By by) {
        this.by = by;
    }

    @Override
    public String toString() {
        return name;
    }


    /**
     * Get element text.
     *
     * @return String text
     */
    public String getText() {
    	return (String) doAction(ACTION_NAME.GET_TEXT, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()));
    }

    /**
     * Get element location.
     *
     * @return Point location
     */
    public Point getLocation() {
    	return (Point) doAction(ACTION_NAME.GET_LOCATION, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()));
    }

    /**
     * Get element size.
     *
     * @return Dimension size
     */
    public Dimension getSize() {
    	return (Dimension) doAction(ACTION_NAME.GET_SIZE, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()));
    }

    /**
     * Get element attribute.
     *
     * @param name of attribute
     * @return String attribute value
     */
    public String getAttribute(String name) {
    	return (String) doAction(ACTION_NAME.GET_ATTRIBUTE, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()), name);
    }

    /**
     * Click on element.
     */
    public void click() {
        click(EXPLICIT_TIMEOUT);
    }

    /**
     * Click on element.
     *
     * @param timeout to wait
     */
    public void click(long timeout) {
        click(timeout, ExpectedConditions.elementToBeClickable(getBy()));
    }
    
	/**
	 * Click on element.
	 *
	 * @param timeout to wait
	 * @param waitCondition
	 *            to check element conditions before action
	 */
    public void click(long timeout, ExpectedCondition<WebElement> waitCondition) {
    	doAction(ACTION_NAME.CLICK, timeout, waitCondition);
    }

    /**
     * Double Click on element.
     */
    public void doubleClick() {
    	doubleClick(EXPLICIT_TIMEOUT);
    }
    
    /**
     * Double Click on element.
     *
     * @param timeout to wait
     */
    public void doubleClick(long timeout) {
    	doubleClick(timeout, ExpectedConditions.elementToBeClickable(getBy()));
    }
    /**
     * Double Click on element.
     *
     * @param timeout to wait
	 * @param waitCondition
	 *            to check element conditions before action
     */
    public void doubleClick(long timeout, ExpectedCondition<WebElement> waitCondition) {
    	doAction(ACTION_NAME.DOUBLE_CLICK, timeout, waitCondition);
    }

    
    /**
     * Mouse RightClick on element.
     */
    public void rightClick() {
    	rightClick(EXPLICIT_TIMEOUT);
    }
    
    /**
     * Mouse RightClick on element.
     *
     * @param timeout to wait
     */
    public void rightClick(long timeout) {
    	rightClick(timeout, ExpectedConditions.elementToBeClickable(getBy()));
    }
    
    /**
     * Mouse RightClick on element.
     *
     * @param timeout to wait
	 * @param waitCondition
	 *            to check element conditions before action
     */
    public void rightClick(long timeout, ExpectedCondition<WebElement> waitCondition) {
    	doAction(ACTION_NAME.RIGHT_CLICK, timeout, waitCondition);
    }
    

    /**
     * MouseOver (Hover) an element.
     */
    public void hover() {
        hover(null, null);
    }

    /**
     * MouseOver (Hover) an element.
	 * @param xOffset x offset for moving
	 * @param yOffset y offset for moving
     */
    public void hover(Integer xOffset, Integer yOffset) {
    	doAction(ACTION_NAME.HOVER, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()), xOffset, yOffset);
    }
    
    /**
     * Click Hidden Element. useful when element present in DOM but actually is
     * not visible. And can't be clicked by standard click.
     */
    @Deprecated
    public void clickHiddenElement() {
    	clickHiddenElement(EXPLICIT_TIMEOUT);
    }
    
    /**
     * Click Hidden Element. useful when element present in DOM but actually is
     * not visible. And can't be clicked by standard click.
     *
     * @param timeout to wait
     */
    @Deprecated
    public void clickHiddenElement(long timeout) {
    	click(timeout);
    }

    /**
     * Click onto element if it present.
     *
     * @return boolean return true if clicked
     */
    public boolean clickIfPresent() {
        return clickIfPresent(EXPLICIT_TIMEOUT);
    }

    /**
     * Click onto element if present.
     *
     * @param timeout - timeout
     * @return boolean return true if clicked
     */
    public boolean clickIfPresent(long timeout) {
        boolean present = isElementPresent(timeout);
        if (present) {
            captureElements();
            click();
        }

        return present;
    }

    
    /**
     * Send Keys to element.
     * 
	 * @param keys Keys
     */
    public void sendKeys(Keys keys) {
    	sendKeys(keys, EXPLICIT_TIMEOUT);
    }

    /**
     * Send Keys to element.
     *
	 * @param keys Keys
     * @param timeout to wait
     */
    public void sendKeys(Keys keys, long timeout) {
    	sendKeys(keys, timeout, ExpectedConditions.presenceOfElementLocated(getBy()));
    }
    
	/**
	 * Send Keys to element.
	 *
	 * @param keys Keys
	 * @param timeout to wait
	 * @param waitCondition
	 *            to check element conditions before action
	 */
    public void sendKeys(Keys keys, long timeout, ExpectedCondition<WebElement> waitCondition) {
    	doAction(ACTION_NAME.SEND_KEYS, timeout, waitCondition, keys);
    }
    
    
    /**
     * Type text to element.
     * 
	 * @param text String
     */
    public void type(String text) {
    	type(text, EXPLICIT_TIMEOUT);
    }

    /**
     * Type text to element.
     *
	 * @param text String
     * @param timeout to wait
     */
    public void type(String text, long timeout) {
    	type(text, timeout, ExpectedConditions.presenceOfElementLocated(getBy()));
    }
    
	/**
	 * Type text to element.
	 *
	 * @param text String
	 * @param timeout to wait
	 * @param waitCondition
	 *            to check element conditions before action
	 */
    public void type(String text, long timeout, ExpectedCondition<WebElement> waitCondition) {
    	doAction(ACTION_NAME.TYPE, timeout, waitCondition, text);
    }
    
    /**
    /**
     * Scroll to element (applied only for desktop).
     */
    @Deprecated
    public void scrollTo() {
        if (Configuration.getDriverType().equals(SpecialKeywords.MOBILE)) {
            LOGGER.debug("scrollTo javascript is unsupported for mobile devices!");
            return;
        }
        try {
            Locatable locatableElement = (Locatable) findElement(EXPLICIT_TIMEOUT);
            // [VD] onScreen should be updated onto onPage as only 2nd one
            // returns real coordinates without scrolling... read below material
            // for details
            // https://groups.google.com/d/msg/selenium-developers/nJR5VnL-3Qs/uqUkXFw4FSwJ

            // [CB] onPage -> inViewPort
            // https://code.google.com/p/selenium/source/browse/java/client/src/org/openqa/selenium/remote/RemoteWebElement.java?r=abc64b1df10d5f5d72d11fba37fabf5e85644081
            int y = locatableElement.getCoordinates().inViewPort().getY();
            int offset = R.CONFIG.getInt("scroll_to_element_y_offset");
            ((JavascriptExecutor) getDriver()).executeScript("window.scrollBy(0," + (y - offset) + ");");
        } catch (Exception e) {
        	//do nothing
        }
    }
     
    /* Inputs file path to specified element.
     *
     * @param filePath path
     */
    public void attachFile(String filePath) {
    	doAction(ACTION_NAME.ATTACH_FILE, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()), filePath);
    }

    /**
     * Check checkbox
     * <p>
     * for checkbox Element
     */
    public void check() {
    	doAction(ACTION_NAME.CHECK, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()));
    }

    /**
     * Uncheck checkbox
     * <p>
     * for checkbox Element
     */
    public void uncheck() {
    	doAction(ACTION_NAME.UNCHECK, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()));
    }

    /**
     * Get checkbox state.
     *
     * @return - current state
     */
    public boolean isChecked() {
    	return (boolean) doAction(ACTION_NAME.IS_CHECKED, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()));
    }

    /**
     * Get selected elements from one-value select.
     *
     * @return selected value
     */
    public String getSelectedValue() {
    	return (String) doAction(ACTION_NAME.GET_SELECTED_VALUE, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()));
    }

    /**
     * Get selected elements from multi-value select.
     *
     * @return selected values
     */
    @SuppressWarnings("unchecked")
	public List<String> getSelectedValues() {
    	return (List<String>) doAction(ACTION_NAME.GET_SELECTED_VALUES, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()));
    }

    /**
     * Selects text in specified select element.
     *
     * @param selectText select text
     * @return true if item selected, otherwise false.
     */
    public boolean select(final String selectText) {
    	return (boolean) doAction(ACTION_NAME.SELECT, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()), selectText);
    }

    /**
     * Select multiple text values in specified select element.
     *
     * @param values final String[]
     * @return boolean.
     */
    public boolean select(final String[] values) {
    	return (boolean) doAction(ACTION_NAME.SELECT_VALUES, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()), values);
    }

    /**
     * Selects value according to text value matcher.
     *
     * @param matcher {@link} BaseMatcher
     * @return true if item selected, otherwise false.
     *         <p>
     *         Usage example: BaseMatcher&lt;String&gt; match=new
     *         BaseMatcher&lt;String&gt;() { {@literal @}Override public boolean
     *         matches(Object actual) { return actual.toString().contains(RequiredText);
     *         } {@literal @}Override public void describeTo(Description description) {
     *         } };
     */
    public boolean selectByMatcher(final BaseMatcher<String> matcher) {
    	return (boolean) doAction(ACTION_NAME.SELECT_BY_MATCHER, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()), matcher);
    }

    /**
     * Selects first value according to partial text value.
     *
     * @param partialSelectText select by partial text
     * @return true if item selected, otherwise false.
     */
    public boolean selectByPartialText(final String partialSelectText) {
    	return (boolean) doAction(ACTION_NAME.SELECT_BY_PARTIAL_TEXT, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()), partialSelectText);
    }

    /**
     * Selects item by index in specified select element.
     *
     * @param index to select by
     * @return true if item selected, otherwise false.
     */
    public boolean select(final int index) {
    	return (boolean) doAction(ACTION_NAME.SELECT_BY_INDEX, EXPLICIT_TIMEOUT, ExpectedConditions.presenceOfElementLocated(getBy()), index);
    }

    // --------------------------------------------------------------------------
    // Base UI validations
    // --------------------------------------------------------------------------
    /**
     * Check that element present and visible.
     *
     * @return element existence status.
     */
    public boolean isElementPresent() {
    	return isElementPresent(EXPLICIT_TIMEOUT);
    }

    /**
     * Check that element present and visible within specified timeout.
     *
     * @param timeout - timeout.
     * @return element existence status.
     */
    public boolean isElementPresent(long timeout) {
    	return waitUntil(ExpectedConditions.and(ExpectedConditions.presenceOfElementLocated(getBy()),
    			ExpectedConditions.visibilityOfElementLocated(getBy())), timeout);
    }

    /**
     * Check that element not present and not visible within specified timeout.
     *
     * @param timeout - timeout.
     * @return element existence status.
     */
    public boolean isElementNotPresent(long timeout) {
        return !isElementPresent(timeout);
    }

    /**
     * Check that element with text present.
     *
     * @param text of element to check.
     * @return element with text existence status.
     */
    public boolean isElementWithTextPresent(final String text) {
        return isElementWithTextPresent(text, EXPLICIT_TIMEOUT);
    }

    /**
     * Check that element with text present.
     *
     * @param text of element to check.
     * @param timeout - timeout.
     * @return element with text existence status.
     */
    public boolean isElementWithTextPresent(final String text, long timeout) {
    	final String decryptedText = cryptoTool.decryptByPattern(text, CRYPTO_PATTERN);
    	return waitUntil(ExpectedConditions.and(ExpectedConditions.presenceOfElementLocated(getBy()),
				ExpectedConditions.textToBe(getBy(), decryptedText)), timeout);
    }
    
    public void assertElementWithTextPresent(final String text) {
        assertElementWithTextPresent(text, EXPLICIT_TIMEOUT);
    }

    public void assertElementWithTextPresent(final String text, long timeout) {
        if (!isElementWithTextPresent(text, timeout)) {
            Assert.fail(Messager.ELEMENT_WITH_TEXT_NOT_PRESENT.getMessage(getNameWithLocator(), text));
        }
    }
    
    public void assertElementPresent() {
        assertElementPresent(EXPLICIT_TIMEOUT);
    }

    public void assertElementPresent(long timeout) {
		if (!isPresent(timeout)) {
			Assert.fail(Messager.ELEMENT_NOT_PRESENT.getMessage(getNameWithLocator()));
		}
    }



    /**
     * Find Extended Web Element on page using By starting search from this
     * object.
     *
     * @param by Selenium By locator
     * @return ExtendedWebElement if exists otherwise null.
     */
    public ExtendedWebElement findExtendedWebElement(By by) {
        return findExtendedWebElement(by, by.toString(), EXPLICIT_TIMEOUT);
    }

    /**
     * Find Extended Web Element on page using By starting search from this
     * object.
     *
     * @param by Selenium By locator
     * @param timeout to wait
     * @return ExtendedWebElement if exists otherwise null.
     */
    public ExtendedWebElement findExtendedWebElement(By by, long timeout) {
        return findExtendedWebElement(by, by.toString(), timeout);
    }

    /**
     * Find Extended Web Element on page using By starting search from this
     * object.
     *
     * @param by Selenium By locator
     * @param name Element name
     * @return ExtendedWebElement if exists otherwise null.
     */
    public ExtendedWebElement findExtendedWebElement(final By by, String name) {
        return findExtendedWebElement(by, name, EXPLICIT_TIMEOUT);
    }

    /**
     * Find Extended Web Element on page using By starting search from this
     * object.
     *
     * @param by Selenium By locator
     * @param name Element name
     * @param timeout Timeout to find
     * @return ExtendedWebElement if exists otherwise null.
     */
    public ExtendedWebElement findExtendedWebElement(final By by, String name, long timeout) {
        if (isPresent(by, timeout)) {
        	return new ExtendedWebElement(getElement().findElement(by), name);
        } else {
        	throw new RuntimeException("Unable to find dynamic element using By: " + by.toString());
        }
    }

    public List<ExtendedWebElement> findExtendedWebElements(By by) {
        return findExtendedWebElements(by, EXPLICIT_TIMEOUT);
    }

    public List<ExtendedWebElement> findExtendedWebElements(final By by, long timeout) {
        List<ExtendedWebElement> extendedWebElements = new ArrayList<ExtendedWebElement>();
        List<WebElement> webElements = new ArrayList<WebElement>();
        
        if (isPresent(by, timeout)) {
        	webElements = getElement().findElements(by);
        } else {
        	throw new RuntimeException("Unable to find dynamic elements using By: " + by.toString());
        }

        for (WebElement element : webElements) {
            String name = "undefined";
            try {
                name = element.getText();
            } catch (Exception e) {
                /* do nothing */
                LOGGER.debug(e.getMessage(), e.getCause());
            }

            extendedWebElements.add(new ExtendedWebElement(element, name));
        }
        return extendedWebElements;
    }

    @Deprecated
    public void tapWithCoordinates(double x, double y) {
        HashMap<String, Double> tapObject = new HashMap<String, Double>();
        tapObject.put("x", x);
        tapObject.put("y", y);
        final WebDriver drv = getDriver();
        JavascriptExecutor js = (JavascriptExecutor) drv;
        js.executeScript("mobile: tap", tapObject);
    }

    @Deprecated
    public void waitUntilElementNotPresent(final long timeout) {
    	waitUntilElementDissappear(timeout);
    }
    
    public boolean waitUntilElementDissappear(final long timeout) {
		return waitUntil(ExpectedConditions.stalenessOf(element) , timeout);
    }

    /**
     * is Element Not Present After Wait
     *
     * @param timeout in seconds
     * @return boolean - false if element still present after wait - otherwise
     *         true if it disappear
     */
    @Deprecated
    public boolean isElementNotPresentAfterWait(final long timeout) {
    	return waitUntilElementDissappear(timeout);
    }
    /**
     * Checks that element clickable.
     *
     * @return element clickability status.
     */
    public boolean isClickable() {
        return isClickable(EXPLICIT_TIMEOUT);
    }

    /**
     * Check that element clickable within specified timeout.
     *
     * @param timeout - timeout.
     * @return element clickability status.
     */
    public boolean isClickable(long timeout) {
    	return waitUntil(ExpectedConditions.elementToBeClickable(getBy()), timeout);
    }

    /**
     * Checks that element visible.
     *
     * @return element visibility status.
     */
    public boolean isVisible() {
        return isVisible(EXPLICIT_TIMEOUT);
    }

    /**
     * Check that element visible within specified timeout.
     *
     * @param timeout - timeout.
     * @return element visibility status.
     */
    public boolean isVisible(long timeout) {
    	return waitUntil(ExpectedConditions.visibilityOfElementLocated(getBy()), timeout);
    }

    public ExtendedWebElement format(Object... objects) {
        String locator = by.toString();
        By by = null;

        if (locator.startsWith("By.id: ")) {
            by = By.id(String.format(StringUtils.remove(locator, "By.id: "), objects));
        }

        if (locator.startsWith("By.name: ")) {
            by = By.name(String.format(StringUtils.remove(locator, "By.name: "), objects));
        }

        if (locator.startsWith("By.xpath: ")) {
            by = By.xpath(String.format(StringUtils.remove(locator, "By.xpath: "), objects));
        }
        if (locator.startsWith("linkText: ")) {
            by = By.linkText(String.format(StringUtils.remove(locator, "linkText: "), objects));
        }

        if (locator.startsWith("partialLinkText: ")) {
            by = By.linkText(String.format(StringUtils.remove(locator, "partialLinkText: "), objects));
        }

        if (locator.startsWith("css: ")) {
            by = By.cssSelector(String.format(StringUtils.remove(locator, "css: "), objects));
        }

        if (locator.startsWith("tagName: ")) {
            by = By.tagName(String.format(StringUtils.remove(locator, "tagName: "), objects));
        }

        /*
         * All ClassChain locators start from **. e.g FindBy(xpath = "**'/XCUIElementTypeStaticText[`name CONTAINS[cd] '%s'`]")
         */
        if (locator.startsWith("By.IosClassChain: **")) {
            by = MobileBy.iOSClassChain(String.format(StringUtils.remove(locator, "By.IosClassChain: "), objects));
        }
        
        if (locator.startsWith("By.IosNsPredicate: **")) {
            by = MobileBy.iOSNsPredicateString(String.format(StringUtils.remove(locator, "By.IosNsPredicate: "), objects));
        }

        return new ExtendedWebElement(by, name, getDriver());
    }

    private void captureElements() {
    	//TODO: take a look again when we need this functionality again
    	return;

/*        if (!Configuration.getBoolean(Parameter.SMART_SCREENSHOT)) {
            return;
        }

        if (!BrowserType.CHROME.equalsIgnoreCase(Configuration.get(Parameter.BROWSER))) {
            return;
        }

        String currentUrl;
        if (!Configuration.get(Parameter.BROWSER).isEmpty()) {
            currentUrl = driver.getCurrentUrl();
        } else {
            // change for XBox and looks like mobile part
            currentUrl = driver.getTitle();
        }

        String cache = getUrlWithoutParameters(currentUrl);
        if (!MetadataCollector.getAllCollectedData().containsKey(cache)) {
            try {

                ElementsInfo elementsInfo = new ElementsInfo();
                elementsInfo.setCurrentURL(currentUrl);

                String metadataScreenPath = Screenshot.captureMetadata(getDriver(), String.valueOf(cache.hashCode()));
                // TODO: double check that file exist because due to the
                // different reason screenshot can miss
                File newPlace = new File(metadataScreenPath);

                ScreenShootInfo screenShootInfo = new ScreenShootInfo();
                screenShootInfo.setScreenshotPath(newPlace.getAbsolutePath());
                BufferedImage bimg = ImageIO.read(newPlace);

                screenShootInfo.setWidth(bimg.getWidth());
                screenShootInfo.setHeight(bimg.getHeight());
                elementsInfo.setScreenshot(screenShootInfo);

                List<WebElement> all = driver.findElements(By.xpath("//*"));

                List<WebElement> control = driver.findElements(
                        By.xpath("//input[not(contains(@type,'hidden'))] | //button | .//*[contains(@class, 'btn') and not(self::span)] | //select"));

                for (WebElement webElement : control) {
                    ElementInfo elementInfo = getElementInfo(new ExtendedWebElement(webElement, driver));

                    int elementPosition = all.indexOf(webElement);
                    for (int i = 1; i < 5; i++) {
                        if (elementPosition - i < 0) {
                            break;
                        }

                        if (control.indexOf(all.get(elementPosition - i)) > 0) {
                            break;
                        }
                        if (!all.get(elementPosition - i).isDisplayed()) {
                            continue;
                        }
                        String sti = all.get(elementPosition - i).getText();
                        if (sti == null || sti.isEmpty() || control.get(0).getText().equals(sti)) {
                            continue;
                        } else {
                            elementInfo.setTextInfo(getElementInfo(new ExtendedWebElement(all.get(elementPosition - i), driver)));
                            break;
                        }
                    }
                    elementsInfo.addElement(elementInfo);
                    MetadataCollector.putPageInfo(cache, elementsInfo);
                }

            } catch (IOException e) {
                LOGGER.error("Unable to capture elements metadata!", e);
            } catch (Exception e) {
                LOGGER.error("Unable to capture elements metadata!", e);
            } catch (Throwable thr) {
                LOGGER.error("Unable to capture elements metadata!", thr);
            }
        }*/
    }

    /*    private String getUrlWithoutParameters(String url) {

        try {
            URI uri = new URI(url);
            return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, uri.getFragment()).toString();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        return url;
    }

    @SuppressWarnings("unchecked")
    private ElementInfo getElementInfo(ExtendedWebElement extendedWebElement) {
        ElementInfo elementInfo = new ElementInfo();
        if (extendedWebElement.isElementPresent(1)) {
            Point location = extendedWebElement.getElement().getLocation();
            Dimension size = extendedWebElement.getElement().getSize();
            elementInfo.setRect(new Rect(location.getX(), location.getY(), size.getWidth(), size.getHeight()));
            elementInfo.setElementsAttributes(
                    (Map<String, String>) ((RemoteWebDriver) driver).executeScript(ATTRIBUTE_JS, extendedWebElement.getElement()));

            try {
                elementInfo.setText(extendedWebElement.getText());
            } catch (Exception e) {
                elementInfo.setText("");
            }

            return elementInfo;
        } else {
            return null;
        }

    }
*/
    /**
     * Pause for specified timeout.
     * 
     * @param timeout in seconds.
     */

    public void pause(long timeout) {
        CommonUtils.pause(timeout);
    }

    public void pause(double timeout) {
        CommonUtils.pause(timeout);
    }

    
	public interface ActionSteps {
		void doClick();

		void doDoubleClick();

		void doRightClick();
		
		void doHover(Integer xOffset, Integer yOffset);

		void doType(String text);

		void doSendKeys(Keys keys);

		void doAttachFile(String filePath);

		void doCheck();

		void doUncheck();
		
		boolean doIsChecked();
		
		String doGetText();

		Point doGetLocation();

		Dimension doGetSize();

		String doGetAttribute(String name);

		boolean doSelect(String text);

		boolean doSelectValues(final String[] values);

		boolean doSelectByMatcher(final BaseMatcher<String> matcher);

		boolean doSelectByPartialText(final String partialSelectText);

		boolean doSelectByIndex(final int index);
		
		String doGetSelectedValue();
		
		List<String> doGetSelectedValues();
	}

	private Object executeAction(ACTION_NAME actionName, ActionSteps actionSteps, Object...inputArgs) {
		Object result = null;
		switch (actionName) {
		case CLICK:
			actionSteps.doClick();
			break;
		case DOUBLE_CLICK:
			actionSteps.doDoubleClick();
			break;
		case HOVER:
			actionSteps.doHover((Integer) inputArgs[0], (Integer) inputArgs[1]);
			break;
		case RIGHT_CLICK:
			actionSteps.doRightClick();
			break;
		case GET_TEXT:
			result = actionSteps.doGetText();
			break;
		case GET_LOCATION:
			result = actionSteps.doGetLocation();
			break;
		case GET_SIZE:
			result = actionSteps.doGetSize();
			break;
		case GET_ATTRIBUTE:
			result = actionSteps.doGetAttribute((String) inputArgs[0]);
			break;
		case SEND_KEYS:
			actionSteps.doSendKeys((Keys) inputArgs[0]);
			break;
		case TYPE:
			actionSteps.doType((String) inputArgs[0]);
			break;
		case ATTACH_FILE:
			actionSteps.doAttachFile((String) inputArgs[0]);
			break;
		case CHECK:
			actionSteps.doCheck();
			break;
		case UNCHECK:
			actionSteps.doUncheck();
			break;
		case IS_CHECKED:
			result = actionSteps.doIsChecked();
			break;
		case SELECT:
			result = actionSteps.doSelect((String) inputArgs[0]);
			break;
		case SELECT_VALUES:
			result = actionSteps.doSelectValues((String[]) inputArgs);
			break;
		case SELECT_BY_MATCHER:
			result = actionSteps.doSelectByMatcher((BaseMatcher<String>) inputArgs[0]);
			break;
		case SELECT_BY_PARTIAL_TEXT:
			result = actionSteps.doSelectByPartialText((String) inputArgs[0]);
			break;
		case SELECT_BY_INDEX:
			result = actionSteps.doSelectByIndex((int) inputArgs[0]);
			break;
		case GET_SELECTED_VALUE:
			result = actionSteps.doGetSelectedValue();
			break;
		case GET_SELECTED_VALUES:
			result = actionSteps.doGetSelectedValues();
			break;
		default:
			Assert.fail("Unsupported UI action name" + actionName.toString());
			break;
		}
		return result;
	}

	/**
	 * doAction on element.
	 *
	 * @param timeout
	 * @param waitCondition
	 *            to check element conditions before action
	 */
	private Object doAction(ACTION_NAME actionName, long timeout, ExpectedCondition<WebElement> waitCondition) {
		// [VD] do not remove null args otherwise all actions without arguments will be broken!
		Object nullArgs = null;
		return doAction(actionName, timeout, waitCondition, nullArgs);
	}

	private Object doAction(ACTION_NAME actionName, long timeout, ExpectedCondition<WebElement> waitCondition,
			Object...inputArgs) {
		if (waitCondition != null) {
			//do verification only if waitCondition is fine
			if (!waitUntil(waitCondition, timeout)) {
				LOGGER.error(Messager.ELEMENT_CONDITION_NOT_VERIFIED.getMessage(getNameWithLocator()));
			}
		}

		Object output = null;
		// captureElements();

		//handle invalid element state: Element is not currently interactable and may not be manipulated
		Timer.start(actionName);
		try {
			element = getCachedElement();
			output = overrideAction(actionName, inputArgs);
		} catch (StaleElementReferenceException | InvalidElementStateException e) {
			LOGGER.debug("catched StaleElementReferenceException | InvalidElementStateException: ", e);
			// try to find again using driver
			element = refindElement(getBy(), 1);

			output = overrideAction(actionName, inputArgs);
		} catch (Throwable e) {
			LOGGER.error(e.getMessage(), e);
			// print error messages according to the action type
			throw e;
		} finally {
			Timer.stop(actionName);
		}

		return output;
	}

	// single place for all supported UI actions in carina core
	private Object overrideAction(ACTION_NAME actionName, Object...inputArgs) {
		Object output = executeAction(actionName, new ActionSteps() {
			@Override
			public void doClick() {
				try {
					DriverListener.setMessages(Messager.ELEMENT_CLICKED.getMessage(getName()),
							Messager.ELEMENT_NOT_CLICKED.getMessage(getNameWithLocator()));
					element.click();
				} catch (WebDriverException e) {
					if (e != null && (e.getMessage().contains("Other element would receive the click:"))) {
						LOGGER.warn("Trying to do click by Actions due to the: " + e.getMessage());
						Actions actions = new Actions(getDriver());
						actions.moveToElement(element).click().perform();
						
						//TODO: analyze if we should try to click using js as well
						//JavascriptExecutor executor = (JavascriptExecutor) getDriver();
			            //executor.executeScript("arguments[0].click();", element);
					} else if (e != null && (e.getMessage().contains("is not visible on the screen and thus is not interactable"))) {
						// https://github.com/facebook/WebDriverAgent/issues/602
						LOGGER.warn("Trying to do tap by coordinates temporary due to the: " + e.getMessage());
						Point point = getLocation();
						Dimension dim = getSize();
						MobileUtils.tap(point.getX() + dim.getWidth() / 2, point.getY() + dim.getHeight() / 2);
					} else {
						throw e;
					}
				}
			}

			@Override
			public void doDoubleClick() {
				DriverListener.setMessages(Messager.ELEMENT_DOUBLE_CLICKED.getMessage(getName()),
						Messager.ELEMENT_NOT_DOUBLE_CLICKED.getMessage(getNameWithLocator()));
				
				WebDriver drv = getDriver();
				Actions action = new Actions(drv);
				action.moveToElement(element).doubleClick(element).build().perform();
			}
			
			@Override
			public void doHover(Integer xOffset, Integer yOffset) {
				DriverListener.setMessages(Messager.ELEMENT_HOVERED.getMessage(getName()),
						Messager.ELEMENT_NOT_HOVERED.getMessage(getNameWithLocator()));
				
				WebDriver drv = getDriver();
				Actions action = new Actions(drv);
				if (xOffset != null && yOffset!= null) {
					action.moveToElement(element, xOffset, yOffset).build().perform();
				} else {
					action.moveToElement(element).build().perform();
				}
			}
			
			@Override
			public void doSendKeys(Keys keys) {
				DriverListener.setMessages(Messager.KEYS_SEND_TO_ELEMENT.getMessage(keys.toString(), getName()),
						Messager.KEYS_NOT_SEND_TO_ELEMENT.getMessage(keys.toString(), getNameWithLocator()));
				element.sendKeys(keys);
			}

			@Override
			public void doType(String text) {
				final String decryptedText = cryptoTool.decryptByPattern(text, CRYPTO_PATTERN);
				DriverListener.setMessages(Messager.KEYS_SEND_TO_ELEMENT.getMessage(decryptedText, getName()),
						Messager.KEYS_NOT_SEND_TO_ELEMENT.getMessage(decryptedText, getNameWithLocator()));

				element.clear();
				element.sendKeys(decryptedText);
			}

			@Override
			public void doAttachFile(String filePath) {
				final String decryptedText = cryptoTool.decryptByPattern(filePath, CRYPTO_PATTERN);
				
				DriverListener.setMessages(Messager.FILE_ATTACHED.getMessage(decryptedText, getName()),
						Messager.FILE_NOT_ATTACHED.getMessage(decryptedText, getNameWithLocator()));

				((JavascriptExecutor) getDriver()).executeScript("arguments[0].style.display = 'block';", element);
				((RemoteWebDriver) getDriver()).setFileDetector(new LocalFileDetector());
				element.sendKeys(decryptedText);
			}

			@Override
			public String doGetText() {
				String text = element.getText();
				LOGGER.debug(Messager.ELEMENT_ATTRIBUTE_FOUND.getMessage("Text", text, getName()));
				return text;
			}

			@Override
			public Point doGetLocation() {
				Point point = element.getLocation();
				LOGGER.debug(Messager.ELEMENT_ATTRIBUTE_FOUND.getMessage("Location", point.toString(), getName()));
				return point;
			}

			@Override
			public Dimension doGetSize() {
				Dimension dim = element.getSize();
				LOGGER.debug(Messager.ELEMENT_ATTRIBUTE_FOUND.getMessage("Size", dim.toString(), getName()));
				return dim;
			}

			@Override
			public String doGetAttribute(String name) {
				String attribute = element.getAttribute(name);
				LOGGER.debug(Messager.ELEMENT_ATTRIBUTE_FOUND.getMessage(name, attribute, getName()));
				return attribute;
			}

			@Override
			public void doRightClick() {
				DriverListener.setMessages(Messager.ELEMENT_RIGHT_CLICKED.getMessage(getName()),
						Messager.ELEMENT_NOT_RIGHT_CLICKED.getMessage(getNameWithLocator()));
				
				WebDriver drv = getDriver();
				Actions action = new Actions(drv);
				action.moveToElement(element).contextClick(element).build().perform();
			}

			@Override
			public void doCheck() {
				DriverListener.setMessages(Messager.CHECKBOX_CHECKED.getMessage(getName()), null);
				
				if (!element.isSelected()) {
					click();
				}
			}

			@Override
			public void doUncheck() {
				DriverListener.setMessages(Messager.CHECKBOX_UNCHECKED.getMessage(getName()), null);
				if (element.isSelected()) {
					click();
				}
			}
			
			@Override
			public boolean doIsChecked() {
				
		        boolean res = element.isSelected();
		        if (element.getAttribute("checked") != null) {
		            res |= element.getAttribute("checked").equalsIgnoreCase("true");
		        }
		        
		        return res;
			}
			
			@Override
			public boolean doSelect(String text) {
				final String decryptedSelectText = cryptoTool.decryptByPattern(text, CRYPTO_PATTERN);
				
				DriverListener.setMessages(Messager.SELECT_BY_TEXT_PERFORMED.getMessage(decryptedSelectText, getName()),
						Messager.SELECT_BY_TEXT_NOT_PERFORMED.getMessage(decryptedSelectText, getNameWithLocator()));

				
				final Select s = new Select(element);
				s.selectByVisibleText(decryptedSelectText);
				return true;
			}

			@Override
			public boolean doSelectValues(String[] values) {
				boolean result = true;
				for (String value : values) {
					if (!select(value)) {
						result = false;
					}
				}
				return result;
			}

			@Override
			public boolean doSelectByMatcher(BaseMatcher<String> matcher) {
				
				DriverListener.setMessages(Messager.SELECT_BY_MATCHER_TEXT_PERFORMED.getMessage(matcher.toString(), getName()),
						Messager.SELECT_BY_MATCHER_TEXT_NOT_PERFORMED.getMessage(matcher.toString(), getNameWithLocator()));

				
				final Select s = new Select(element);
				String fullTextValue = null;
				for (WebElement option : s.getOptions()) {
					if (matcher.matches(option.getText())) {
						fullTextValue = option.getText();
						break;
					}
				}
				s.selectByVisibleText(fullTextValue);
				return true;
			}

			@Override
			public boolean doSelectByPartialText(String partialSelectText) {
				
				DriverListener.setMessages(
						Messager.SELECT_BY_TEXT_PERFORMED.getMessage(partialSelectText, getName()),
						Messager.SELECT_BY_TEXT_NOT_PERFORMED.getMessage(partialSelectText, getNameWithLocator()));
				
				final Select s = new Select(element);
				String fullTextValue = null;
				for (WebElement option : s.getOptions()) {
					if (option.getText().contains(partialSelectText)) {
						fullTextValue = option.getText();
						break;
					}
				}
				s.selectByVisibleText(fullTextValue);
				return true;
			}

			@Override
			public boolean doSelectByIndex(int index) {
				DriverListener.setMessages(
						Messager.SELECT_BY_INDEX_PERFORMED.getMessage(String.valueOf(index), getName()),
						Messager.SELECT_BY_INDEX_NOT_PERFORMED.getMessage(String.valueOf(index), getNameWithLocator()));
				
				
				final Select s = new Select(element);
				s.selectByIndex(index);
				return true;
			}

			@Override
			public String doGetSelectedValue() {
				final Select s = new Select(element);
				return s.getAllSelectedOptions().get(0).getText();
			}

			@Override
			public List<String> doGetSelectedValues() {
		        final Select s = new Select(getElement());
		        List<String> values = new ArrayList<String>();
		        for (WebElement we : s.getAllSelectedOptions()) {
		            values.add(we.getText());
		        }
		        return values;
			}
			
		}, inputArgs);
		return output;
	}

    private WebDriver getDriver() {
		if (driver != null) {
			return driver;
		} else {
			try {
				throw new RuntimeException("review stacktrace to analyze why tempBy is not populated correctly via reflection!");
			} catch (Throwable thr) {
				thr.printStackTrace();
			}
			
			LOGGER.error("Unable to detect driver! Looking for default one from pool.");
			return DriverPool.getDriver();
		}
    }
}