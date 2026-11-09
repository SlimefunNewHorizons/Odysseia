package org.metamechanists.odysseia.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.jetbrains.annotations.NotNull;
import org.metamechanists.odysseia.purchase.*;

import java.util.List;
import java.util.UUID;

/** Consola administrativa y entrada única de Tebex. */
public final class PurchaseCommand implements CommandExecutor {
    private final PurchaseEngine engine;
    public PurchaseCommand(PurchaseEngine engine) { this.engine = engine; }

    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("odysseia.purchase.admin")) return message(sender, false, "Sin permiso.");
        PurchaseService service = engine.service();
        if (service == null) return message(sender, false, "Motor no disponible: " + engine.startupError());
        if (args.length == 0) return usage(sender, label);
        try {
            return switch (args[0].toLowerCase()) {
                case "deliver" -> args.length == 4 ? deliver(sender, service, args) : usage(sender, label);
                case "renew" -> args.length == 4 || args.length == 5 ? renew(sender, service, args) : usage(sender, label);
                case "test" -> args.length == 3 ? test(sender, service, args) : usage(sender, label);
                case "dry-run" -> args.length == 3 ? result(sender, service.deliver("dry-run:" + System.nanoTime(), args[1], args[2], true, sender.getName())) : usage(sender, label);
                case "retry" -> args.length == 2 ? result(sender, service.retry(args[1], sender.getName())) : usage(sender, label);
                case "retry-action" -> args.length == 3 ? result(sender, service.retryAction(args[1], Long.parseLong(args[2]), sender.getName())) : usage(sender, label);
                case "refund" -> args.length == 3 ? result(sender, service.financialEvent(args[1], args[2], false, sender.getName())) : usage(sender, label);
                case "chargeback" -> args.length == 3 ? result(sender, service.financialEvent(args[1], args[2], true, sender.getName())) : usage(sender, label);
                case "status" -> args.length == 2 ? deliveries(sender, service.status(args[1])) : usage(sender, label);
                case "pending" -> deliveries(sender, service.pending());
                case "history" -> args.length == 2 ? deliveries(sender, service.history(args[1])) : usage(sender, label);
                case "validate" -> message(sender, service.catalog().validate().isEmpty(), service.catalog().validate().isEmpty() ? "Catálogo válido: " + service.catalog().all().size() + " productos." : String.join("; ", service.catalog().validate()));
                case "catalog" -> { service.catalog().all().forEach(product -> sender.sendMessage(product.id() + " | " + product.name() + " | " + product.verification())); yield true; }
                case "reconcile" -> result(sender, service.reconcile(sender.getName()));
                case "manual-review", "complete", "cancel" -> args.length == 2 ? result(sender, service.adminState(args[1], args[0], sender.getName())) : usage(sender, label);
                default -> usage(sender, label);
            };
        } catch (Exception error) { return message(sender, false, error.getMessage()); }
    }

    private boolean deliveries(CommandSender sender, List<PurchaseRepository.Delivery> deliveries) {
        if (deliveries.isEmpty()) return message(sender, true, "Sin resultados.");
        deliveries.forEach(item -> sender.sendMessage(item.transaction() + " | " + item.player() + " | " + item.product() + " | " + item.state())); return true;
    }

    /**
     * Tebex Bukkit has historically dispatched the three delivery arguments in a
     * different order. Normalize at the command boundary so the purchase engine
     * always receives transaction, player and stable product id in that order.
     */
    private boolean deliver(CommandSender sender, PurchaseService service, String[] args) {
        DeliveryArguments delivery = DeliveryArguments.from(service.catalog(), args[1], args[2], args[3]);
        return result(sender, service.deliver(delivery.transaction(), delivery.player(), delivery.productId(), false, sender.getName()));
    }

    private boolean renew(CommandSender sender, PurchaseService service, String[] args) {
        DeliveryArguments d = DeliveryArguments.from(service.catalog(), args[1], args[2], args[3]);
        return result(sender, service.renew(d.transaction(), d.player(), d.productId(), args.length == 5 ? args[4] : null, sender.getName()));
    }

    /**
     * Prueba operativa del mismo circuito que invoca Tebex: registra la compra,
     * entrega las acciones del producto y encola su anuncio/webhook. Se limita a
     * consola porque entrega recompensas reales y deja una auditoría distinguible.
     */
    private boolean test(CommandSender sender, PurchaseService service, String[] args) {
        if (!(sender instanceof ConsoleCommandSender)) {
            return message(sender, false, "La prueba de compra sólo puede ejecutarse desde consola.");
        }
        return result(sender, service.deliver(testTransaction(), args[1], args[2], false, "CONSOLE_TEST"));
    }

    static String testTransaction() {
        return "test-" + UUID.randomUUID();
    }

    static record DeliveryArguments(String transaction, String player, String productId) {
        static DeliveryArguments from(ProductCatalog catalog, String first, String second, String third) {
            ProductDefinition p3 = catalog.get(third);
            if (p3 != null) return new DeliveryArguments(first, second, p3.id());
            ProductDefinition p2 = catalog.get(second);
            if (p2 != null) return new DeliveryArguments(third, first, p2.id());
            throw new IllegalArgumentException("Producto inexistente: '" + second + "' ni '" + third + "' coinciden con un producto en purchases.yml");
        }
    }

    private boolean result(CommandSender sender, PurchaseService.Result result) { return message(sender, result.success(), result.message()); }
    private boolean message(CommandSender sender, boolean ok, String value) { sender.sendMessage((ok ? ChatColor.GREEN : ChatColor.RED) + value); return true; }
    private boolean usage(CommandSender sender, String label) { sender.sendMessage(ChatColor.YELLOW + "/" + label + " deliver <transaction> <username> <product_id> (Tebex legado: <username> <product_id> <transaction>) | test <username> <product_id> (sólo consola, entrega real) | dry-run <username> <product_id> | renew <transaction> <username> <product_id> [ref] (comando Renewal de Tebex) | status|pending|history|retry|retry-action|refund|chargeback|validate|catalog|reconcile"); return true; }
}
