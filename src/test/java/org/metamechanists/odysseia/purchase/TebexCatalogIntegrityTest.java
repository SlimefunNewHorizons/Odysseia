package org.metamechanists.odysseia.purchase;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Prevents publishing a store catalog that cannot be matched to a real Tebex purchase. */
class TebexCatalogIntegrityTest {

    @Test
    void everyProductHasARealUniquePackageIdAndResolvableKit() {
        YamlConfiguration purchases = YamlConfiguration.loadConfiguration(
                new File("src/main/resources/purchases.yml"));
        YamlConfiguration config = YamlConfiguration.loadConfiguration(
                new File("src/main/resources/config.yml"));
        ConfigurationSection products = purchases.getConfigurationSection("products");
        Set<String> definedKits = config.getConfigurationSection("kits") == null
                ? Set.of()
                : config.getConfigurationSection("kits").getKeys(false);

        List<String> errors = new ArrayList<>();
        Set<Integer> seenPackageIds = new HashSet<>();
        assertEquals(purchases.getInt("expected-product-count"), products.getKeys(false).size(),
                "expected-product-count no coincide con products");

        for (String productId : products.getKeys(false)) {
            int packageId = products.getInt(productId + ".tebex-package-id");
            if (packageId <= 0) errors.add(productId + ": tebex-package-id vacio o invalido");
            if (ProductCatalog.isPlaceholderTebexPackageId(packageId)) {
                errors.add(productId + ": tebex-package-id placeholder " + packageId);
            }
            if (!seenPackageIds.add(packageId)) errors.add(productId + ": tebex-package-id duplicado " + packageId);

            for (Map<?, ?> action : products.getMapList(productId + ".actions")) {
                if (!"KIT".equalsIgnoreCase(String.valueOf(action.get("type")))) continue;
                Object rawParameters = action.get("parameters");
                if (!(rawParameters instanceof Map<?, ?> parameters)) {
                    errors.add(productId + ": accion KIT sin parameters");
                    continue;
                }
                String kit = String.valueOf(parameters.get("kit"));
                if (!definedKits.contains(kit)) errors.add(productId + ": kit inexistente '" + kit + "'");
            }
        }

        assertEquals(List.of(), errors,
                "El catalogo Tebex contiene productos no publicables: " + String.join("; ", errors));
    }

    @Test
    void sfmasterIsNotBundledInRanksAndHalloweenKitIsRetired() {
        YamlConfiguration purchases = YamlConfiguration.loadConfiguration(new File("src/main/resources/purchases.yml"));
        ConfigurationSection products = purchases.getConfigurationSection("products");
        assertEquals(false, products.contains("kit_halloween_tartaro"), "kit de temporada vencido: no debe entregarse");
        List<String> errors = new ArrayList<>();
        for (String id : products.getKeys(false)) {
            if (id.startsWith("sfmaster_")) continue; // pases sueltos: solo se venden si el paquete Tebex esta activo
            for (Map<?, ?> action : products.getMapList(id + ".actions")) {
                String type = String.valueOf(action.get("type"));
                if (type.startsWith("SFMASTER")) errors.add(id + ": " + type);
                if (String.valueOf(action.get("parameters")).contains("{username}")) errors.add(id + ": {username} sin sustituir");
            }
        }
        assertEquals(List.of(), errors);
    }
}
