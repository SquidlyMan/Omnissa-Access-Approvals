package com.omnissa.access.approval.util;

import com.omnissa.access.approval.exception.MyConfigurationAlreadyExistsException;
import com.omnissa.access.approval.exception.MyConfigurationMissingException;
import com.omnissa.access.approval.exception.MyResourceNotFoundException;
import com.omnissa.access.approval.model.OmnissaServer;
import com.omnissa.access.approval.repository.OmnissaServerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RestPreconditions {

    private static OmnissaServerRepository serverRepository;

    @Autowired
    public RestPreconditions(OmnissaServerRepository serverRepository) {
        RestPreconditions.serverRepository = serverRepository;
    }

    public static <T> T checkNotNull(final T reference, final String message) {
        if (reference == null) {
            throw new MyResourceNotFoundException(message);
        }
        return reference;
    }

    public static String omnissaServerBaseUrl() {
        return "https://" + omnissaServerConfig().getUrl();
    }

    public static OmnissaServer omnissaServerConfig() {
        checkConfigAvailability();
        return serverRepository.findAll().get(0);
    }

    public static void checkConfigAvailability() {
        if (serverRepository.count() == 0) {
            throw new MyConfigurationMissingException(
                    "An Omnissa Access configuration could not be found. " +
                    "POST a valid OmnissaServer object to '/api/config/server' first.");
        }
    }

    public static void checkIfConfigExists() {
        if (serverRepository.count() >= 1) {
            throw new MyConfigurationAlreadyExistsException(
                    "An Omnissa Access configuration already exists. " +
                    "Delete the existing configuration before adding a new one.");
        }
    }
}
