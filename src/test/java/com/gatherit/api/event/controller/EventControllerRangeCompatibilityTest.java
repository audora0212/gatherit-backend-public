package com.gatherit.api.event.controller;

import com.gatherit.api.event.service.EventService;
import com.gatherit.api.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EventControllerRangeCompatibilityTest {
    private static final Long USER_ID = 7L;
    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-01T00:00:00Z");

    private EventService events;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        events = mock(EventService.class);
        UserDetailsImpl me = mock(UserDetailsImpl.class);
        when(me.getUserId()).thenReturn(USER_ID);
        mvc = MockMvcBuilders.standaloneSetup(new EventController(events))
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.getParameterType() == UserDetailsImpl.class;
                    }

                    @Override
                    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                                  NativeWebRequest webRequest,
                                                  org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                        return me;
                    }
                })
                .build();
    }

    @Test
    void oldAppRequestDefaultsToLegacyRange() throws Exception {
        when(events.findInRange(USER_ID, FROM, TO, false)).thenReturn(List.of());

        mvc.perform(get("/api/events")
                        .param("from", FROM.toString())
                        .param("to", TO.toString()))
                .andExpect(status().isOk());

        verify(events).findInRange(USER_ID, FROM, TO, false);
    }

    @Test
    void newAppRequestOptsInToRecurringSources() throws Exception {
        when(events.findInRange(USER_ID, FROM, TO, true)).thenReturn(List.of());

        mvc.perform(get("/api/events")
                        .param("from", FROM.toString())
                        .param("to", TO.toString())
                        .param("includeRecurringSources", "true"))
                .andExpect(status().isOk());

        verify(events).findInRange(USER_ID, FROM, TO, true);
    }
}
