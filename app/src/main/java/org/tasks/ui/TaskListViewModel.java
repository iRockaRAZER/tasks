package org.tasks.ui;

import static com.todoroo.andlib.sql.Field.field;
import static com.todoroo.astrid.activity.TaskListFragment.CALDAV_METADATA_JOIN;
import static com.todoroo.astrid.activity.TaskListFragment.GTASK_METADATA_JOIN;
import static com.todoroo.astrid.activity.TaskListFragment.TAGS_METADATA_JOIN;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;
import androidx.sqlite.db.SimpleSQLiteQuery;
import com.google.common.collect.Lists;
import com.todoroo.andlib.data.Property.StringProperty;
import com.todoroo.andlib.sql.Criterion;
import com.todoroo.andlib.sql.Field;
import com.todoroo.andlib.sql.Join;
import com.todoroo.andlib.sql.Query;
import com.todoroo.astrid.api.CaldavFilter;
import com.todoroo.astrid.api.Filter;
import com.todoroo.astrid.api.GtasksFilter;
import com.todoroo.astrid.api.PermaSql;
import com.todoroo.astrid.api.TagFilter;
import com.todoroo.astrid.core.SortHelper;
import com.todoroo.astrid.dao.Database;
import com.todoroo.astrid.dao.TaskDao;
import com.todoroo.astrid.data.Task;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import java.util.List;
import javax.inject.Inject;
import org.tasks.data.CaldavTask;
import org.tasks.data.GoogleTask;
import org.tasks.data.Tag;
import org.tasks.data.TaskContainer;
import org.tasks.preferences.Preferences;
import timber.log.Timber;

public class TaskListViewModel extends ViewModel {

  private static final Field TASKS = field("*");
  private static final StringProperty GTASK =
      new StringProperty(null, GTASK_METADATA_JOIN + ".list_id").as("googletask");
  private static final StringProperty CALDAV =
      new StringProperty(null, CALDAV_METADATA_JOIN + ".calendar").as("caldav");
  private static final Field INDENT = field("google_tasks.indent").as("indent");
  private static final StringProperty TAGS =
      new StringProperty(null, "group_concat(" + TAGS_METADATA_JOIN + ".tag_uid" + ", ',')")
          .as("tags");
  private final MutableLiveData<List<TaskContainer>> tasks = new MutableLiveData<>();
  @Inject Preferences preferences;
  @Inject TaskDao taskDao;
  @Inject Database database;
  private Filter filter;
  private CompositeDisposable disposable = new CompositeDisposable();

  public void observe(
      LifecycleOwner owner, @NonNull Filter filter, Observer<List<TaskContainer>> observer) {
    if (!filter.equals(this.filter) || !filter.getSqlQuery().equals(this.filter.getSqlQuery())) {
      this.filter = filter;
      invalidate();
    }
    tasks.observe(owner, observer);
  }

  private String getQuery(Filter filter) {
    List<Field> fields = Lists.newArrayList(TASKS, TAGS, GTASK, CALDAV);

    Criterion tagsJoinCriterion = Criterion.and(Task.ID.eq(field(TAGS_METADATA_JOIN + ".task")));
    Criterion gtaskJoinCriterion =
        Criterion.and(
            Task.ID.eq(field(GTASK_METADATA_JOIN + ".task")),
            field(GTASK_METADATA_JOIN + ".deleted").eq(0));
    Criterion caldavJoinCriterion =
        Criterion.and(
            Task.ID.eq(field(CALDAV_METADATA_JOIN + ".task")),
            field(CALDAV_METADATA_JOIN + ".deleted").eq(0));
    if (filter instanceof TagFilter) {
      String uuid = ((TagFilter) filter).getUuid();
      tagsJoinCriterion =
          Criterion.and(tagsJoinCriterion, field(TAGS_METADATA_JOIN + ".tag_uid").neq(uuid));
    } else if (filter instanceof GtasksFilter) {
      String listId = ((GtasksFilter) filter).getRemoteId();
      gtaskJoinCriterion =
          Criterion.and(gtaskJoinCriterion, field(GTASK_METADATA_JOIN + ".list_id").neq(listId));
      fields.add(INDENT);
    } else if (filter instanceof CaldavFilter) {
      String uuid = ((CaldavFilter) filter).getUuid();
      caldavJoinCriterion =
          Criterion.and(caldavJoinCriterion, field(CALDAV_METADATA_JOIN + ".calendar").neq(uuid));
    }

    // TODO: For now, we'll modify the query to join and include the things like tag data here.
    // Eventually, we might consider restructuring things so that this query is constructed
    // elsewhere.
    String joinedQuery =
        Join.left(Tag.TABLE.as(TAGS_METADATA_JOIN), tagsJoinCriterion).toString() // $NON-NLS-1$
            + Join.left(GoogleTask.TABLE.as(GTASK_METADATA_JOIN), gtaskJoinCriterion).toString()
            + Join.left(CaldavTask.TABLE.as(CALDAV_METADATA_JOIN), caldavJoinCriterion).toString()
            + filter.getSqlQuery();

    String query =
        SortHelper.adjustQueryForFlagsAndSort(preferences, joinedQuery, preferences.getSortMode());

    String groupedQuery;
    if (query.contains("GROUP BY")) {
      groupedQuery = query;
    } else if (query.contains("ORDER BY")) {
      groupedQuery = query.replace("ORDER BY", "GROUP BY " + Task.ID + " ORDER BY"); // $NON-NLS-1$
    } else {
      groupedQuery = query + " GROUP BY " + Task.ID;
    }

    return Query.select(fields.toArray(new Field[0]))
        .withQueryTemplate(PermaSql.replacePlaceholdersForQuery(groupedQuery))
        .from(Task.TABLE)
        .toString();
  }

  public void searchByFilter(Filter filter) {
    this.filter = filter;
    invalidate();
  }

  public void invalidate() {
    String query = getQuery(filter);
    Timber.v(query);
    disposable.add(
        Single.fromCallable(() -> taskDao.fetchTasks(new SimpleSQLiteQuery(query)))
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(tasks::setValue, Timber::e));
  }

  @Override
  protected void onCleared() {
    disposable.dispose();
  }
}
