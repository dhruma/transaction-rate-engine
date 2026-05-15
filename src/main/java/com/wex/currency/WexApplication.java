package com.wex.currency;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the WEX Purchase Currency Service.
 *
 * <p>The service satisfies two requirements from the product brief:
 * <ol>
 *   <li>Store a purchase transaction (description, date, USD amount) with a generated id.</li>
 *   <li>Retrieve a stored transaction converted into a Treasury-supported currency using the
 *       most recent exchange rate on or before the purchase date, within the prior 6 months.</li>
 * </ol>
 */
@SpringBootApplication
public class WexApplication {

    public static void main(String[] args) {
        SpringApplication.run(WexApplication.class, args);
    }
}
