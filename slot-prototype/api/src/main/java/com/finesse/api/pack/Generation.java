package com.finesse.api.pack;

import java.util.List;

/** mode = server | template. server면 servers 1개 이상 */
public record Generation(String mode, List<String> servers) {

    public static final String SERVER = "server";
    public static final String TEMPLATE = "template";
}
