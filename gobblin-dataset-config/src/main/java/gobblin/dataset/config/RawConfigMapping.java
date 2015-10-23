package gobblin.dataset.config;

import java.util.*;

import com.typesafe.config.*;



public class RawConfigMapping {

  //private static final Logger LOG = Logger.getLogger(RawConfigMapping.class);
  private final Map<String, Map<String, Object>> configMap = new HashMap<String, Map<String, Object>>();
  private final Config allConfigs;

  public RawConfigMapping(Config allConfigs) {
    this.allConfigs = allConfigs;
    initialMapping();
  }

  private void initialMapping() {
    for (Map.Entry<String, ConfigValue> entry : allConfigs.entrySet()) {
      // constant variables should be resolved already
      if(entry.getKey().startsWith(DatasetUtils.CONSTS_PREFIX+DatasetUtils.ID_DELEMETER)){
        continue;
      }
      
      if (DatasetUtils.isValidUrn(entry.getKey())) {

        String parentId = DatasetUtils.getParentId(entry.getKey());
        String propertyName = DatasetUtils.getPropertyName(entry.getKey());
        Object value = entry.getValue().unwrapped();
        
        // check import-tags are valid entry
        checkAssociatedTags(propertyName, value);

        Map<String, Object> submap = configMap.get(parentId);
        if (submap == null) {
          submap = new HashMap<String, Object>();
          configMap.put(parentId, submap);
        }
        submap.put(propertyName, value);
      }
      // invalid urn
      else {
        throw new IllegalArgumentException("Invalid urn " + entry.getKey());
      }
    }
  }
  
  private void checkAssociatedTags(String key, Object value){
    if(!key.equals(DatasetUtils.IMPORTED_TAGS)) return;
    
    if(value instanceof String){
      String tag = (String)value;
      if( !DatasetUtils.isValidTag( tag ) ) {
        throw new RuntimeException("Invalid tag: " + tag);
      }
    }
    
    if(value instanceof List){
      @SuppressWarnings("unchecked")
      List<String> tags = (List<String>) value;
      for(String tag: tags){
        if( !DatasetUtils.isValidTag( tag ) ) {
          throw new RuntimeException("Invalid tag: " + tag);
        }
      }
    }
  }

  // return the closest urn which is specified in the config
  public String getAdjustedUrn(String urn){
    if(urn.equals(DatasetUtils.ROOT)){
      return urn;
    }

    if(!DatasetUtils.isValidUrn(urn)){
      throw new IllegalArgumentException("Invalid urn:" + urn);
    }

    if(configMap.containsKey(urn)){
      return urn;
    }

    return getAdjustedUrn(DatasetUtils.getParentId(urn));
  }
  
  private List<String> getUrnTillAdjustedUrn(String urn, String adjusted){
    List<String> res = new ArrayList<String>();
    res.add(urn);
    
    String parentId = DatasetUtils.getParentId(urn);
    while(!parentId.equals(adjusted)){
      res.add(parentId);
      parentId = DatasetUtils.getParentId(parentId);
    }
    return res;
  }

  // get own properties, not resolved yet
  public Map<String, Object> getRawProperty(String urn) {
    String adjustedUrn = this.getAdjustedUrn(urn);
    Map<String, Object> res = configMap.get(adjustedUrn);
    
    if(res == null){
      return Collections.emptyMap();
    }
    // need to return deep copy
    return new HashMap<String, Object>(res);
  }

  @SuppressWarnings("unchecked")
  public List<String> getRawTags(String urn){
    Map<String, Object> self = getRawProperty(urn);
    Object tags = self.get(DatasetUtils.IMPORTED_TAGS);

    List<String> res=new ArrayList<String>();
    if(tags == null) return res;
    
    if(tags instanceof String){
      res.add((String)tags);
    }
    else {
      // need to return deep copy
      // and added it in reverse order
      List<String> tmp = (List<String>)tags;
      for(int i=tmp.size()-1; i>=0; i--){
        res.add(tmp.get(i));
      }
    }
    
    // tag refer to self
    for(String s:res){
      if(s.equals(urn)){
        // may have circular dependency for children as well
        throw new TagCircularDependencyException("Tag associated with self or children " + s);
      }
    }

    return res;
  }
  
  public List<String> getAssociatedTags(String urn){
    if(urn.equals(DatasetUtils.ROOT)){
      return Collections.emptyList();
    }
    return this.getAssociatedTagsWithCheck(urn, new HashSet<String>());
  }
  
  private List<String> getAssociatedTagsWithCheck(String urn, Set<String> previousTags){
    List<String> self = getRawTags(urn);
    List<String> res = new ArrayList<String>();
    

    for(String s: self){
      if(previousTags.contains(s)) {
        throw new TagCircularDependencyException("Circular dependence for tag " + s);
      }
      
      res.add(s);
      Set<String> combined = new HashSet<String>(previousTags);
      combined.add(s);
      
      res.addAll(getAssociatedTagsWithCheck(s, combined));
    }
    
    String parentId = DatasetUtils.getParentId(getAdjustedUrn(urn));
    if(!parentId.equals(DatasetUtils.ROOT)){
      // check circular dependency for self chain
      Set<String> selfChain = new HashSet<String>(getUrnTillAdjustedUrn(urn, parentId));
      List<String> ancestorTags = getAssociatedTagsWithCheck(DatasetUtils.getParentId(getAdjustedUrn(urn)), selfChain);
      res.addAll(ancestorTags);
    }
    
    return dedup(res);
  }
  
  private List<String> dedup(List<String> input){
    Set<String> processed = new HashSet<String> ();
    List<String> res = new ArrayList<String> ();
    for(String s: input){
      if(processed.contains(s)){
        continue;
      }
      
      res.add(s);
      processed.add(s);
    }
    return res;
  }

  // get resolved property: self union ancestor union tags
  public Map<String, Object> getResolvedProperty(String urn){
    // self is deep copy
    Map<String, Object> self = getRawProperty(urn);
    
    List<String> tags = getAssociatedTags(urn);
    for(String t: tags){
      DatasetUtils.MergeTwoMaps(self, getResolvedProperty(t));
    }

    String parentId = DatasetUtils.getParentId(getAdjustedUrn(urn));
    Map<String, Object> ancestor = null;
    if(parentId.equals(DatasetUtils.ROOT)){
      ancestor = this.getRawProperty(parentId);
    }
    else {
      ancestor = this.getResolvedProperty(parentId);
    }
    

    Map<String, Object> res = DatasetUtils.MergeTwoMaps(self, ancestor);
    // TODO
//    if(res!=null){
//      res.remove(DatasetUtils.IMPORTED_TAGS);
//    }
    return res;
  }
  
  public Map<String, Config> getTaggedConfig(String inputTag){
    if(!DatasetUtils.isValidTag(inputTag)) return null;
    
    Map<String, Config> res = new HashMap<String, Config>();
    
    for(String urnKey: this.configMap.keySet()){
      List<String> rawTags = this.getRawTags(urnKey);
      if(rawTags!=null){
        for(String tag: rawTags){
          if(tag.equals(inputTag)){
            res.put(urnKey, ConfigFactory.parseMap(this.getResolvedProperty(urnKey)));
          }
        }
      }
    }
    return res;
  }
}
