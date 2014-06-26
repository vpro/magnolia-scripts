import info.magnolia.cms.core.MgnlNodeType
import info.magnolia.jcr.predicate.JCRMgnlPropertyHidingPredicate
import info.magnolia.jcr.util.MetaDataUtil
import info.magnolia.jcr.util.NodeUtil
import info.magnolia.jcr.util.NodeVisitor
import info.magnolia.jcr.util.SessionUtil
import info.magnolia.module.templatingkit.templates.pages.STKPage
import info.magnolia.objectfactory.Components
import info.magnolia.registry.RegistrationException
import info.magnolia.rendering.template.TemplateDefinition
import info.magnolia.rendering.template.registry.TemplateDefinitionRegistry
import info.magnolia.repository.RepositoryConstants
import net.sf.json.JSONObject
import org.apache.jackrabbit.commons.predicate.Predicate
import org.slf4j.LoggerFactory

import javax.jcr.*

/**
 * Generates a layout for a website tree as a JSON object, which can be very
 * helpful when doing migrations.
 *
 * The generated layout contains the following:
 *
 * - Pages (by template), areas (by name), components (by template) and
 *   properties (by name and type).
 * - Page structure as a tree of templates.
 * - Occurrence and activation counts.
 *
 * JSON support was added in Groovy 1.8, but we're still on 1.7 right now. :(
 * http://docs.codehaus.org/display/GroovyJSR/GEP+7+-+JSON+Support
 *
 * Using json-lib instead for now.
 *
 * You'll want to inspect the JSON output in a viewer that supports
 * expanding and collapsing nodes. :)
 */

/*****************
 * Configuration *
 *****************/

String path = '/demo-project'

/***********
 * Globals *
 ***********/

log = LoggerFactory.getLogger('GenerateWebsiteTreeLayout')

/*************
 * Functions *
 *************/

Node getWebsiteNode(String path) {
    return SessionUtil.getNode(RepositoryConstants.WEBSITE, path)
}

Map<String, Object> getOrCreateMap(Map<String, Object> map, String submapName) {
    Map<String, Object> submap = (Map<String, Object>) map.get(submapName)
    if (submap == null) {
        submap = new TreeMap<String, Object>()
    }
    return submap
}

void addToPages(Map<String, Object> pages, Node pageNode) {
    String pageTemplate = getTemplate(pageNode)

    Map<String, Object> page = getOrCreateMap(pages, pageTemplate)
    addOrIncrementCounts(page, pageNode)

    // Find template category and subcategory, which only need to be evaluated once per template
    if (!pages.containsKey(pageTemplate)) {
        try {
            TemplateDefinition templateDefinition = Components.getComponent(TemplateDefinitionRegistry.class).getTemplateDefinition(pageTemplate)
            if (templateDefinition instanceof STKPage) {
                STKPage stkPage = (STKPage) templateDefinition
                page.put('category', stkPage.getCategory())
                page.put('subcategory', stkPage.getSubcategory())
            } else {
                log.warn "'${pageTemplate}' is not an STK template, can't add category and subcategory"
            }
        } catch (RegistrationException ignored) {
            log.warn "No STK template definition found for template '${pageTemplate}', can't add category and subcategory"
        }
    }

    // Areas
    Map<String, Object> pageAreas = getOrCreateMap(page, 'areas')
    for (Node pageArea : NodeUtil.getNodes(pageNode, MgnlNodeType.NT_AREA)) {
        addToAreas(pageAreas, pageArea)
    }
    if (!pageAreas.isEmpty()) {
        page.put('areas', pageAreas)
    }

    // Properties
    Map<String, Object> pageProperties = getOrCreateMap(page, 'properties')
    addToProperties(pageProperties, pageNode)
    if (!pageProperties.isEmpty()) {
        page.put('properties', pageProperties)
    }

    // Save new page
    if (!page.isEmpty()) {
        pages.put(pageTemplate, page)
    }
}

void addToAreas(Map<String, Object> areas, Node areaNode) {
    String areaName = areaNode.getName()
    Map<String, Object> area = getOrCreateMap(areas, areaName)
    addOrIncrementCounts(area, areaNode)

    // Subareas
    Map<String, Object> subareas = getOrCreateMap(area, 'areas')
    for (Node subarea : NodeUtil.getNodes(areaNode, MgnlNodeType.NT_AREA)) {
        addToAreas(subareas, subarea)
    }
    if (!subareas.isEmpty()) {
        area.put('areas', subareas)
    }

    // Area components
    Map<String, Object> areaComponents = getOrCreateMap(area, 'components')
    for (Node component : NodeUtil.getNodes(areaNode, MgnlNodeType.NT_COMPONENT)) {
        addToComponents(areaComponents, component)
    }
    if (!areaComponents.isEmpty()) {
        area.put('components', areaComponents)
    }

    // Area properties
    Map<String, Object> areaProperties = getOrCreateMap(area, 'properties')
    addToProperties(areaProperties, areaNode)
    if (!areaProperties.isEmpty()) {
        area.put('properties', areaProperties)
    }

    areas.put(areaName, area)
}

void addToComponents(Map<String, Object> components, Node componentNode) {
    String componentTemplate = getTemplate(componentNode)
    Map<String, Object> component = getOrCreateMap(components, componentTemplate)
    addOrIncrementCounts(component, componentNode)

    // Component areas
    Map<String, Object> componentAreas = getOrCreateMap(component, 'areas')
    for (Node componentArea : NodeUtil.getNodes(componentNode, MgnlNodeType.NT_AREA)) {
        addToAreas(componentAreas, componentArea)
    }
    if (!componentAreas.isEmpty()) {
        component.put('areas', componentAreas)
    }

    // Component properties
    Map<String, Object> componentProperties = getOrCreateMap(component, 'properties')
    addToProperties(componentProperties, componentNode)
    if (!componentProperties.isEmpty()) {
        component.put('properties', componentProperties)
    }

    components.put(componentTemplate, component)
}

void addToProperties(Map<String, Object> properties, Node node) {
    PropertyIterator propertyIterator = node.getProperties()
    Predicate jcrMgnlPropertyHidingPredicate = new JCRMgnlPropertyHidingPredicate()

    while (propertyIterator.hasNext()) {
        Property property = propertyIterator.nextProperty()
        if (jcrMgnlPropertyHidingPredicate.evaluateTyped(property)) {
            String propertyName = property.getName()
            String propertyType = PropertyType.nameFromValue(property.getType())
            String key = propertyName + ' (' + propertyType + ')'

            Map<String, Object> propertyEntry = getOrCreateMap(properties, key)
            addOrIncrementCounts(propertyEntry, node)
            properties.put(key, propertyEntry)
        }
    }
}

void addToStructure(Map<String, Object> structure, Node root, Node page) {
    List<String> templatesFromRootToPage = getTemplatesFromRootToPage(root, page)
    Map<String, Object> parent = structure
    for (String template : templatesFromRootToPage) {
        Map<String, Object> child = getOrCreateMap(parent, template)
        parent.put(template, child)
        parent = child
    }
}

List<String> getTemplatesFromRootToPage(Node root, Node page) {
    LinkedList<String> listOfTemplatesFromRootToPage = []
    while (page.getPath() != root.getPath()) {
        String pageTemplate = getTemplate(page)
        listOfTemplatesFromRootToPage.addFirst(pageTemplate)
        page = page.getParent()
    }
    String rootTemplate = getTemplate(root)
    listOfTemplatesFromRootToPage.addFirst(rootTemplate)
    return listOfTemplatesFromRootToPage
}

void addOrIncrementCounts(Map<String, Object> map, Node node) {
    Integer count = (Integer) map.get('count')
    if (count == null) {
        count = 0
    }
    map.put('count', count + 1)

    Integer activatedCount = (Integer) map.get('activated')
    if (activatedCount == null) {
        activatedCount = 0
    }
    if (isActivated(node)) {
        activatedCount += 1
    }
    map.put('activated', activatedCount)
}

String getTemplate(Node node) {
    return MetaDataUtil.getMetaData(node).getTemplate()
}

boolean isPage(Node node) {
    return node.getPrimaryNodeType().getName().equals(MgnlNodeType.NT_PAGE)
}

boolean hasChildPages(Node node) {
    return NodeUtil.getNodes(node, MgnlNodeType.NT_PAGE).iterator().hasNext()
}

boolean isActivated(Node node) {
    return MetaDataUtil.getMetaData(node).getIsActivated()
}

/***********
 * Action! *
 ***********/

Node root = getWebsiteNode(path)

if (root) {
    log.info "Generating website tree layout for ${path}"

    Map<String, Object> pages = new TreeMap<String, Object>()
    Map<String, Object> structure = new TreeMap<String, Object>()

    NodeUtil.visit(root, new NodeVisitor() {

        @Override
        void visit(Node node) throws RepositoryException {
            if (isPage(node)) {
                addToPages(pages, node)
                if (!hasChildPages(node)) {
                    addToStructure(structure, root, node)
                }
            }
        }
    })

    JSONObject json = new JSONObject()
    json.put('path', path)
    json.put('pages', pages)
    json.put('structure', structure)
    println json.toString()

    log.info "Done generating website tree layout for ${path}"
} else {
    String message = "Page ${path} does not exist"
    log.error message
    println message
}
